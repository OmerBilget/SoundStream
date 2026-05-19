// audio_sender.cpp

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <avrt.h>
#include <opus.h>
#include <iostream>
#include <vector>
#include <chrono>

#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "avrt.lib")

#define SAMPLE_RATE 48000
#define CHANNELS 2
#define FRAME_SIZE 960


// PACKET HEADER
#pragma pack(push, 1)
struct PacketHeader {
    uint32_t seq;
    uint64_t timestamp; // ms
};
#pragma pack(pop)


// UDP SOCKET
SOCKET create_socket(const char* ip, int port) {

    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);

    SOCKET sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);

    if (inet_pton(AF_INET, ip, &addr.sin_addr) <= 0) {
        std::cerr << "Invalid IP\n";
        exit(-1);
    }

    connect(sock, (sockaddr*)&addr, sizeof(addr));

    return sock;
}


// TIME
uint64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::high_resolution_clock::now().time_since_epoch()
    ).count();
}


// MAIN
int main(int argc, char* argv[]) {

    if (argc < 2) {
        std::cout << "Usage:\n";
        std::cout << "audio_sender.exe <IP> [PORT=5005] [BITRATE=64000]\n";
        return -1;
    }

    const char* ip = argv[1];
    int port = (argc > 2) ? atoi(argv[2]) : 5005;
    int bitrate = (argc > 3) ? atoi(argv[3]) : 64000;

    std::cout << "IP: " << ip << " PORT: " << port << " BITRATE: " << bitrate << "\n";

    SOCKET sock = create_socket(ip, port);


    // COM + WASAPI
    CoInitialize(nullptr);

    IMMDeviceEnumerator* enumerator = nullptr;
    IMMDevice* device = nullptr;

    CoCreateInstance(
        __uuidof(MMDeviceEnumerator),
        nullptr,
        CLSCTX_ALL,
        __uuidof(IMMDeviceEnumerator),
        (void**)&enumerator
    );

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

    if (err != OPUS_OK) {
        std::cerr << "Opus init failed\n";
        return -1;
    }

    opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(5));
    opus_encoder_ctl(encoder, OPUS_SET_INBAND_FEC(1));


    // RING BUFFER
    std::vector<short> ringBuffer;
    ringBuffer.reserve(FRAME_SIZE * 10);

    unsigned char opusBuffer[4000];
    uint32_t sequence = 0;

    std::cout << "Streaming...\n";


    // LOOP
    while (true) {

        UINT32 packetLength = 0;
        captureClient->GetNextPacketSize(&packetLength);

        while (packetLength > 0) {

            BYTE* data;
            UINT32 frames;
            DWORD flags;

            captureClient->GetBuffer(&data, &frames, &flags, nullptr, nullptr);

            float* input = (float*)data;

            // push into ring buffer
            for (UINT32 i = 0; i < frames * CHANNELS; i++) {
                short s = (short)(input[i] * 32767.0f);
                ringBuffer.push_back(s);
            }

            captureClient->ReleaseBuffer(frames);
            captureClient->GetNextPacketSize(&packetLength);

            // ENCODE EXACT FRAMES
            while (ringBuffer.size() >= FRAME_SIZE * CHANNELS) {

                short pcm[FRAME_SIZE * CHANNELS];

                memcpy(pcm, ringBuffer.data(), sizeof(pcm));

                ringBuffer.erase(
                    ringBuffer.begin(),
                    ringBuffer.begin() + FRAME_SIZE * CHANNELS
                );

                int bytes = opus_encode(
                    encoder,
                    pcm,
                    FRAME_SIZE,
                    opusBuffer,
                    sizeof(opusBuffer)
                );

                if (bytes > 0) {

                    // build packet
                    char packet[4096];

                    PacketHeader header;
                    header.seq = sequence++;
                    header.timestamp = now_ms();

                    memcpy(packet, &header, sizeof(header));
                    memcpy(packet + sizeof(header), opusBuffer, bytes);

                    send(sock, packet, bytes + sizeof(header), 0);
                }
            }
        }

        Sleep(1);
    }

    return 0;
}
