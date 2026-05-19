// OPUS REAL-TIME AUDIO STREAMER (WINDOWS)

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <avrt.h>
#include <opus.h>

#include <iostream>
#include <vector>
#include <thread>
#include <atomic>
#include <chrono>
#include <string>
#include <mutex>

#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "avrt.lib")

#define SAMPLE_RATE 48000
#define CHANNELS 2
#define FRAME_SIZE 960


// GLOBAL STATE
std::atomic<bool> running(true);
std::atomic<bool> streaming(true);
std::atomic<int> bitrate(64000);

// runtime network target
std::atomic<int> targetPort(5005);
std::string targetIP = "127.0.0.1";

//active socket
std::atomic<SOCKET> streamSocket;
std::mutex socketMutex;


// PACKET HEADER
#pragma pack(push, 1)
struct PacketHeader {
    uint32_t seq;
    uint64_t timestamp;
};
#pragma pack(pop)


// TIME
uint64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::high_resolution_clock::now().time_since_epoch()
    ).count();
}


// SOCKET CREATION
SOCKET createSocket(const std::string& ip, int port) {

    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);

    SOCKET sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    inet_pton(AF_INET, ip.c_str(), &addr.sin_addr);

    connect(sock, (sockaddr*)&addr, sizeof(addr));

    return sock;
}


// SOCKET RELOAD (IP/PORT SWITCH)
void updateSocket(const std::string& ip, int port) {

    SOCKET newSock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    inet_pton(AF_INET, ip.c_str(), &addr.sin_addr);

    connect(newSock, (sockaddr*)&addr, sizeof(addr));

    SOCKET old = streamSocket.exchange(newSock);

    if (old != INVALID_SOCKET) {
        closesocket(old);
    }

    std::cout << "[SOCKET] switched -> " << ip << ":" << port << "\n";
}


// CONTROL THREAD
void controlThread(OpusEncoder* encoder, int controlPort) {

    SOCKET sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(controlPort);
    addr.sin_addr.s_addr = INADDR_ANY;

    bind(sock, (sockaddr*)&addr, sizeof(addr));

    char buffer[1024];

    std::cout << "[CONTROL] listening on " << controlPort << "\n";

    while (running) {

        sockaddr_in client{};
        int len = sizeof(client);

        int bytes = recvfrom(sock, buffer, sizeof(buffer) - 1, 0,
            (sockaddr*)&client, &len);

        if (bytes <= 0) continue;

        buffer[bytes] = '\0';
        std::string cmd(buffer);

        std::cout << "[CMD] " << cmd << "\n";

        // bitrate
        if (cmd.find("SET_BITRATE") == 0) {
            int br = atoi(cmd.substr(cmd.find(" ") + 1).c_str());
            bitrate = br;
            opus_encoder_ctl(encoder, OPUS_SET_BITRATE(br));
        }

        // stop/start
        else if (cmd == "STOP") streaming = false;
        else if (cmd == "START") streaming = true;

        // IP SWITCH
        else if (cmd.find("SET_IP") == 0) {
            targetIP = cmd.substr(cmd.find(" ") + 1);
            updateSocket(targetIP, targetPort);
        }

        // PORT SWITCH 
        else if (cmd.find("SET_PORT") == 0) {
            int p = atoi(cmd.substr(cmd.find(" ") + 1).c_str());
            targetPort = p;
            updateSocket(targetIP, targetPort);
        }

        // exit
        else if (cmd == "QUIT") {
            running = false;
        }
    }

    closesocket(sock);
}


// MAIN
int main(int argc, char* argv[]) {

    if (argc < 3) {
        std::cout << "Usage: <IP> <PORT> [CONTROL_PORT] [BITRATE]\n";
        return -1;
    }

    targetIP = argv[1];
    targetPort = atoi(argv[2]);

    int controlPort = (argc > 3) ? atoi(argv[3]) : 6000;
    bitrate = (argc > 4) ? atoi(argv[4]) : 64000;

    SOCKET sock = createSocket(targetIP, targetPort);
    streamSocket = sock;

    std::cout << "STREAM -> " << targetIP << ":" << targetPort << "\n";


    // WASAPI INIT
    CoInitialize(nullptr);

    IMMDeviceEnumerator* enumerator = nullptr;
    IMMDevice* device = nullptr;

    CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr,
        CLSCTX_ALL, __uuidof(IMMDeviceEnumerator),
        (void**)&enumerator);

    enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &device);

    IAudioClient* audioClient = nullptr;
    device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, (void**)&audioClient);

    WAVEFORMATEX* format = nullptr;
    audioClient->GetMixFormat(&format);

    audioClient->Initialize(
        AUDCLNT_SHAREMODE_SHARED,
        AUDCLNT_STREAMFLAGS_LOOPBACK,
        0, 0, format, nullptr
    );

    IAudioCaptureClient* captureClient = nullptr;
    audioClient->GetService(__uuidof(IAudioCaptureClient), (void**)&captureClient);

    audioClient->Start();


    // OPUS
    int err;
    OpusEncoder* encoder = opus_encoder_create(
        SAMPLE_RATE,
        CHANNELS,
        OPUS_APPLICATION_AUDIO,
        &err
    );

    opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(5));

    if (err != OPUS_OK) return -1;

    std::thread ctrl(controlThread, encoder, controlPort);

    std::vector<short> ring;
    ring.reserve(FRAME_SIZE * 10);

    unsigned char opusBuf[4000];
    uint32_t seq = 0;

    std::cout << "STREAMING...\n";

    while (running) {

        UINT32 packetLength = 0;
        captureClient->GetNextPacketSize(&packetLength);

        while (packetLength > 0) {

            BYTE* data;
            UINT32 frames;
            DWORD flags;

            captureClient->GetBuffer(&data, &frames, &flags, nullptr, nullptr);

            float* input = (float*)data;

            for (UINT32 i = 0; i < frames * CHANNELS; i++)
                ring.push_back((short)(input[i] * 32767));

            captureClient->ReleaseBuffer(frames);
            captureClient->GetNextPacketSize(&packetLength);

            while (ring.size() >= FRAME_SIZE * CHANNELS) {

                short pcm[FRAME_SIZE * CHANNELS];
                memcpy(pcm, ring.data(), sizeof(pcm));

                ring.erase(ring.begin(), ring.begin() + FRAME_SIZE * CHANNELS);

                int bytes = opus_encode(
                    encoder,
                    pcm,
                    FRAME_SIZE,
                    opusBuf,
                    sizeof(opusBuf)
                );

                if (bytes > 0 && streaming) {

                    char packet[4096];

                    PacketHeader header;
                    header.seq = seq++;
                    header.timestamp = now_ms();

                    memcpy(packet, &header, sizeof(header));
                    memcpy(packet + sizeof(header), opusBuf, bytes);

                    send(streamSocket.load(), packet, bytes + sizeof(header), 0);
                }
            }
        }

        Sleep(1);
    }

    ctrl.join();
    closesocket(streamSocket.load());
    opus_encoder_destroy(encoder);

    std::cout << "EXITED\n";
    return 0;
}
