using System;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Windows;
using System.Runtime.InteropServices;
using System.Windows.Interop;
using System.Threading;
using System.Threading.Tasks;
using System.Security.Cryptography;

// alias to avoid ambiguity
using Forms = System.Windows.Forms;

namespace SoundStream
{
    public partial class MainWindow : Window
    {
        private Process relayProcess;
        private Forms.NotifyIcon trayIcon;


        // DISCOVERY 
        private UdpClient discoveryClient;
        private CancellationTokenSource discoveryToken;
        private const int DISCOVERY_PORT = 50505;

        //HMAC KEY (SHARED WITH ANDROID)

        private static readonly byte[] HmacKey =
            Encoding.UTF8.GetBytes("SUPER_SECRET_SOUNDSTREAM_KEY_2026");

        public MainWindow()
        {
            InitializeComponent();

            SetupTray();

            StateChanged += Window_StateChanged;
            Closing += MainWindow_Closing;
            Loaded += (_, __) => ApplyTitleBarTheme();

            StartDiscoveryListener();
        }

     
        // HMAC SIGNING
        private string SignMessage(string message)
        {
            using (var hmac = new HMACSHA256(HmacKey))
            {
                byte[] hash = hmac.ComputeHash(Encoding.UTF8.GetBytes(message));
                return Convert.ToBase64String(hash);
            }
        }

        private string PackSecureMessage(string msg)
        {
            string sig = SignMessage(msg);
            return $"{msg}|{sig}";
        }

        private bool VerifyMessage(string msg, string signature)
        {
            string expected = SignMessage(msg);
            return expected == signature;
        }


        // SYSTEM TRAY
        private void SetupTray()
        {
            trayIcon = new Forms.NotifyIcon();

            try
            {
                trayIcon.Icon = new System.Drawing.Icon("Assets/SoundStreamLogo.ico");
            }
            catch
            {
                Log("Tray icon not found");
            }

            trayIcon.Visible = true;
            trayIcon.Text = "SoundStream";

            var menu = new Forms.ContextMenuStrip();
            menu.Items.Add("Open", null, (s, e) => ShowWindow());
            menu.Items.Add("Exit", null, (s, e) => ExitApp());

            trayIcon.ContextMenuStrip = menu;
            trayIcon.DoubleClick += (s, e) => ShowWindow();
        }

        private void Window_StateChanged(object sender, EventArgs e)
        {
            if (WindowState == WindowState.Minimized)
                Hide();
        }

        private void ShowWindow()
        {
            Show();
            WindowState = WindowState.Normal;
            Activate();
        }

        private void ExitApp()
        {
            trayIcon.Visible = false;
            trayIcon.Dispose();
            System.Windows.Application.Current.Shutdown();
        }


        // DISCOVERY LISTENER and HMAC
        private void StartDiscoveryListener()
        {
            discoveryToken = new CancellationTokenSource();

            Task.Run(async () =>
            {
                try
                {
                    discoveryClient = new UdpClient(DISCOVERY_PORT);
                    discoveryClient.EnableBroadcast = true;

                    Log($"[DISCOVERY] Listening on {DISCOVERY_PORT}");

                    while (!discoveryToken.Token.IsCancellationRequested)
                    {
                        var result = await discoveryClient.ReceiveAsync();

                        string msg = Encoding.UTF8.GetString(result.Buffer);
                        string senderIp = result.RemoteEndPoint.Address.ToString();

                        Dispatcher.Invoke(() =>
                        {
                            Log($"[DISCOVERY RX] {msg} from {senderIp}");
                        });

                        HandleDiscoveryMessage(msg, result.RemoteEndPoint);
                    }
                }
                catch (Exception ex)
                {
                    Dispatcher.Invoke(() => Log("[DISCOVERY ERROR] " + ex.Message));
                }
            });
        }


        private void HandleDiscoveryMessage(string msg, IPEndPoint sender)
        {
            if (msg == "SOUNDSTREAM_DISCOVER")
            {
                SendDiscoveryResponse(sender);
            }
            else if (msg.StartsWith("HELLO"))
            {
                var parts = msg.Split('|');

   
                // HELLO | ip | port | signature
                if (parts.Length >= 4)
                {
                    string raw = $"{parts[0]}|{parts[1]}|{parts[2]}"; // message without signature
                    string sig = parts[3];

                    if (!VerifyMessage(raw, sig))
                    {
                        Log("[SECURITY] Invalid handshake rejected");
                        return;
                    }

                    Dispatcher.Invoke(() =>
                    {
                        string ip = parts[1];
                        string port = parts[2];

                        IpInput.Text = ip;
                        PortInput.Text = port;

                        Log($"[HANDSHAKE COMPLETE] {ip}:{port}");

                        //AUTO START C++ SERVER
                        StopRelayServer();
                        StartRelayServerAuto(ip, port);

                    });
                }
            }
        }

        private void SendDiscoveryResponse(IPEndPoint target)
        {
            try
            {
                using (UdpClient client = new UdpClient())
                {
                    string msg = $"SOUNDSTREAM_HERE {GetLocalIP()}";
                    string packet = PackSecureMessage(msg);

                    byte[] data = Encoding.UTF8.GetBytes(packet);
                    client.Send(data, data.Length, target);

                    Log($"[DISCOVERY TX] {msg}");
                }
            }
            catch (Exception ex)
            {
                Log("[DISCOVERY SEND ERROR] " + ex.Message);
            }
        }

        // LOCAL IP
        private string GetLocalIP()
        {
            using (var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, 0))
            {
                socket.Connect("8.8.8.8", 65530);
                var ep = socket.LocalEndPoint as IPEndPoint;
                return ep.Address.ToString();
            }
        }


        // WINDOW THEME
        private void ApplyTitleBarTheme()
        {
            var hwnd = new WindowInteropHelper(this).Handle;
            int useDark = ThemeHelper.IsDarkMode() ? 1 : 0;
            DwmSetWindowAttribute(hwnd, 20, ref useDark, sizeof(int));
        }

        [DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(
            IntPtr hwnd,
            int attr,
            ref int attrValue,
            int attrSize);


        // PROCESS CONTROL
        private void Start_Click(object sender, RoutedEventArgs e)
        {
            if (relayProcess != null && !relayProcess.HasExited)
            {
                Log("Already running");
                return;
            }

            string path = System.IO.Path.Combine(
                AppDomain.CurrentDomain.BaseDirectory,
                "RelayServer.exe");

            if (!System.IO.File.Exists(path))
            {
                Log("RelayServer.exe NOT FOUND!");
                return;
            }

            relayProcess = new Process();
            relayProcess.StartInfo.FileName = path;
            relayProcess.StartInfo.Arguments =
                $"{IpInput.Text} {PortInput.Text} {ControlPortInput.Text} {GetBitrate()}";

            relayProcess.StartInfo.UseShellExecute = false;
            relayProcess.StartInfo.RedirectStandardOutput = true;
            relayProcess.StartInfo.RedirectStandardError = true;
            relayProcess.StartInfo.CreateNoWindow = true;

            relayProcess.OutputDataReceived += (s, ev) => Log(ev.Data);
            relayProcess.ErrorDataReceived += (s, ev) => Log(ev.Data);

            relayProcess.Start();
            relayProcess.BeginOutputReadLine();
            relayProcess.BeginErrorReadLine();

            Log("[STARTED]");
        }

        private void Stop_Click(object sender, RoutedEventArgs e)
        {
            SendCmd("QUIT");

            try
            {
                relayProcess?.Kill(true);
                relayProcess?.Dispose();
                relayProcess = null;
            }
            catch { }

            Log("Stopped");
        }

        private void StartRelayServerAuto(string ip, string port)
        {
            try
            {
                if (relayProcess != null && !relayProcess.HasExited)
                {
                    Log("[AUTO] RelayServer already running");
                    return;
                }

                string path = System.IO.Path.Combine(
                    AppDomain.CurrentDomain.BaseDirectory,
                    "RelayServer.exe");

                if (!System.IO.File.Exists(path))
                {
                    Log("[ERROR] RelayServer.exe not found");
                    return;
                }

                relayProcess = new Process();

                relayProcess.StartInfo.FileName = path;

                relayProcess.StartInfo.Arguments =
                    $"{ip} {port} {ControlPortInput.Text} {GetBitrate()}";

                relayProcess.StartInfo.UseShellExecute = false;
                relayProcess.StartInfo.RedirectStandardOutput = true;
                relayProcess.StartInfo.RedirectStandardError = true;
                relayProcess.StartInfo.CreateNoWindow = true;

                relayProcess.OutputDataReceived += (s, e) => Log(e.Data);
                relayProcess.ErrorDataReceived += (s, e) => Log(e.Data);

                relayProcess.Start();
                relayProcess.BeginOutputReadLine();
                relayProcess.BeginErrorReadLine();

                Log("[AUTO START] RelayServer started");
            }
            catch (Exception ex)
            {
                Log("[AUTO START ERROR] " + ex.Message);
            }
        }

        private void StopRelayServer()
        {
            try
            {
                if (relayProcess != null && !relayProcess.HasExited)
                {
                    Log("[AUTO] Stopping previous RelayServer...");

                    try
                    {
                        SendCmd("QUIT"); 
                    }
                    catch { }

                    relayProcess.Kill(true);
                    relayProcess.Dispose();
                    relayProcess = null;

                    Log("[AUTO] RelayServer stopped");
                }
            }
            catch (Exception ex)
            {
                Log("[STOP ERROR] " + ex.Message);
            }
        }

        // UDP COMMANDS
        private void SendCmd(string cmd)
        {
            try
            {
                using (UdpClient client = new UdpClient())
                {
                    string secure = PackSecureMessage(cmd);
                    byte[] data = Encoding.UTF8.GetBytes(secure);

                    client.Send(data, data.Length, "127.0.0.1",
                        int.Parse(ControlPortInput.Text));
                }

                Log($"> {cmd}");
            }
            catch (Exception ex)
            {
                Log("CMD error: " + ex.Message);
            }
        }


        // CLEANUP
        private void MainWindow_Closing(object sender, System.ComponentModel.CancelEventArgs e)
        {
            try
            {
                discoveryToken?.Cancel();
                discoveryClient?.Close();

                trayIcon.Visible = false;
                trayIcon.Dispose();

                relayProcess?.Kill(true);
                relayProcess?.Dispose();
            }
            catch { }
        }


        // BITRATE and LOGGING
        private string GetBitrate()
        {
            if (ManualBitrateCheck.IsChecked == true &&
                !string.IsNullOrWhiteSpace(ManualBitrateInput.Text))
                return ManualBitrateInput.Text;

            return ((int)BitrateSlider.Value).ToString();
        }

        private void Log(string text)
        {
            if (string.IsNullOrWhiteSpace(text)) return;

            Dispatcher.Invoke(() =>
            {
                LogBox.AppendText(text + "\n");
                LogBox.ScrollToEnd();
            });
        }

        private void BitrateSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (BitrateValue != null)
                BitrateValue.Text = $"{(int)e.NewValue} kbps";
        }

        private void ManualBitrateCheck_Changed(object sender, RoutedEventArgs e)
        {
            ManualBitrateInput.IsEnabled = ManualBitrateCheck.IsChecked == true;
        }

        private void Pause_Click(object sender, RoutedEventArgs e) => SendCmd("STOP");
        private void Resume_Click(object sender, RoutedEventArgs e) => SendCmd("START");
        private void ApplyBitrate_Click(object sender, RoutedEventArgs e) => SendCmd($"SET_BITRATE {GetBitrate()}");
        private void ChangeIp_Click(object sender, RoutedEventArgs e) => SendCmd($"SET_IP {IpInput.Text}");
        private void ChangePort_Click(object sender, RoutedEventArgs e) => SendCmd($"SET_PORT {PortInput.Text}");
    }
}
