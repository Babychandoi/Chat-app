# 💬 Chat P2P Application

<div align="center">

![Java](https://img.shields.io/badge/Java-8+-orange.svg)
![JavaFX](https://img.shields.io/badge/JavaFX-17.0.2-blue.svg)
![License](https://img.shields.io/badge/license-MIT-green.svg)
![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-lightgrey.svg)

**Real-time Event-Driven P2P Communication với Voice & Video Call**

[Features](#-features) • [Demo](#-demo) • [Installation](#-installation) • [Usage](#-usage) • [Architecture](#-architecture) • [Contributing](#-contributing)

</div>

---

## 📖 Giới thiệu

Chat P2P là ứng dụng nhắn tin ngang hàng (peer-to-peer) được phát triển bằng Java và JavaFX, cho phép người dùng giao tiếp trực tiếp với nhau qua mạng LAN **mà không cần máy chủ trung gian**.

### ✨ Highlights

- 🚀 **Zero Server**: Hoàn toàn P2P, không cần infrastructure
- 🔍 **Auto Discovery**: Tự động phát hiện peers trên LAN
- 💬 **Rich Messaging**: Text, emoji, file sharing (max 50MB)
- 👥 **Group Chat**: Tạo và quản lý nhóm với nhiều thành viên
- 📞 **Voice Call**: Cuộc gọi thoại chất lượng cao
- 📹 **Video Call**: Video call với audio sync
- 🎨 **Modern UI**: Giao diện đẹp mắt với JavaFX

---

## 🎯 Features

### ✅ Core Features
- [x] User authentication (login/register)
- [x] Auto peer discovery trên LAN
- [x] Chat 1-1 real-time
- [x] Group chat với multi-members
- [x] File sharing (tất cả các định dạng)
- [x] Voice call P2P
- [x] Video call với audio
- [x] Chat history persistence
- [x] Group synchronization

### 🚧 Roadmap
- [ ] End-to-end encryption
- [ ] NAT traversal (Internet support)
- [ ] Dark mode
- [ ] Message reactions & emojis
- [ ] Screen sharing
- [ ] Group video call (conference)
- [ ] Mobile app (Android/iOS)

---

## 📸 Demo

### Login Screen
```
┌─────────────────────────────┐
│    💬 Chat P2P              │
│                             │
│  ┌───────────────────────┐  │
│  │ Username              │  │
│  └───────────────────────┘  │
│  ┌───────────────────────┐  │
│  │ Password              │  │
│  └───────────────────────┘  │
│  ┌───────────────────────┐  │
│  │   Đăng nhập           │  │
│  └───────────────────────┘  │
│  ┌───────────────────────┐  │
│  │   Đăng ký tài khoản   │  │
│  └───────────────────────┘  │
└─────────────────────────────┘
```

### Main Chat Interface
```
┌──────────────┬───────────────────────────────────────┐
│ 👤 Alice     │  Chat với Bob                    📞 📹│
│ 🟢 Online    ├───────────────────────────────────────┤
│              │                                        │
│ 🔍 Search    │     ┌──────────────────┐            │
│              │     │ Hi Bob!          │  10:30     │
│ ── TIN NHẮN  │     └──────────────────┘            │
│              │                                        │
│ 👤 Bob       │           ┌──────────────────┐       │
│ 🟢 Online    │   10:31   │ Hello Alice!     │       │
│              │           └──────────────────┘       │
│ 👤 Charlie   │                                        │
│ 🟢 Online    │     ┌──────────────────┐            │
│              │     │ 📎 document.pdf  │  10:32     │
│ ── NHÓM      │     │ ⬇ Tải về          │            │
│              │     └──────────────────┘            │
│ 👥 Team      │                                        │
│ 3 thành viên │                                        │
│              ├───────────────────────────────────────┤
│              │ 📎  Nhập tin nhắn...              ➤  │
└──────────────┴───────────────────────────────────────┘
```

### Video Call
```
┌─────────────────────────────────────────────┐
│ Video call với Bob                 [X]      │
├─────────────────────────────────────────────┤
│                                             │
│  ┌─────────────────────────────────────┐   │
│  │                                     │   │
│  │      REMOTE VIDEO (800x600)        │   │
│  │                                     │   │
│  │                        ┌─────────┐ │   │
│  │                        │ LOCAL   │ │   │
│  │                        │ VIDEO   │ │   │
│  │                        └─────────┘ │   │
│  └─────────────────────────────────────┘   │
│                                             │
│         🎤    📷    🔴 Kết thúc            │
└─────────────────────────────────────────────┘
```

---

## 🚀 Installation

### Prerequisites

- **Java JDK 8+** (recommended: JDK 11 or 17)
- **JavaFX SDK 17.0.2+**
- **Maven** (for dependency management)
- **Webcam** (for video call)
- **Microphone** (for voice/video call)

### Dependencies

```xml
<!-- pom.xml -->
<dependencies>
    <!-- JavaFX -->
    <dependency>
        <groupId>org.openjfx</groupId>
        <artifactId>javafx-controls</artifactId>
        <version>17.0.2</version>
    </dependency>
    
    <!-- Webcam Capture -->
    <dependency>
        <groupId>com.github.sarxos</groupId>
        <artifactId>webcam-capture</artifactId>
        <version>0.3.12</version>
    </dependency>
</dependencies>
```

### Build & Run

```bash
# Clone repository
git clone https://github.com/yourusername/chat-p2p.git
cd chat-p2p

# Build với Maven
mvn clean package

# Run application
java -jar target/chat-p2p-1.0.jar

# Hoặc compile thủ công
javac --module-path /path/to/javafx-sdk/lib --add-modules javafx.controls *.java
java --module-path /path/to/javafx-sdk/lib --add-modules javafx.controls P2PChatApp
```

---

## 📚 Usage

### 1. Đăng ký tài khoản

```
Username: alice
Password: ********
➜ Click "Đăng ký tài khoản"
```

### 2. Đăng nhập

```
Username: alice
Password: ********
➜ Click "Đăng nhập"
```

### 3. Chat 1-1

- Danh sách contacts tự động hiển thị các peers đã online
- Click vào contact để bắt đầu chat
- Gõ tin nhắn và Enter hoặc click ➤

### 4. Gửi file

```
📎 Click icon đính kèm
➜ Chọn file (max 50MB)
➜ File tự động gửi và hiển thị
```

### 5. Tạo nhóm

```
➕ Click icon tạo nhóm
➜ Nhập tên nhóm
☑️ Chọn các thành viên
➜ Click "Tạo nhóm"
```

### 6. Voice Call

```
📞 Click icon phone trong chat 1-1
➜ Đợi peer accept
➜ Bắt đầu nói chuyện
🔴 Click "Kết thúc" để dừng
```

### 7. Video Call

```
📹 Click icon video trong chat 1-1
➜ Đợi peer accept
➜ Video/audio streaming tự động
🔴 Click "Kết thúc" để dừng
```

---

## 🏗️ Architecture

### System Overview

```
┌─────────────────────────────────────────────────────────┐
│                   P2P Chat Application                   │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ AuthManager  │  │ ChatManager  │  │ CallManager  │  │
│  │              │  │              │  │              │  │
│  │ - Login      │  │ - Messaging  │  │ - Voice      │  │
│  │ - Register   │  │ - Groups     │  │ - Video      │  │
│  │ - Validate   │  │ - Files      │  │ - Controls   │  │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  │
│         │                  │                  │          │
│         └──────────────────┴──────────────────┘          │
│                           │                              │
│                  ┌────────▼────────┐                     │
│                  │ MainController  │                     │
│                  │ (Orchestrator)  │                     │
│                  └────────┬────────┘                     │
│                           │                              │
│         ┌─────────────────┼─────────────────┐            │
│         │                 │                 │            │
│  ┌──────▼──────┐   ┌─────▼─────┐   ┌──────▼──────┐     │
│  │  Network    │   │   Voice   │   │    Video    │     │
│  │  Manager    │   │   Call    │   │    Call     │     │
│  │             │   │  Manager  │   │   Manager   │     │
│  │ - Discovery │   │           │   │             │     │
│  │ - P2P Conn  │   │ - Audio   │   │ - Webcam    │     │
│  │ - Routing   │   │ - Stream  │   │ - Stream    │     │
│  │ - Sync      │   │           │   │ - Sync      │     │
│  └─────────────┘   └───────────┘   └─────────────┘     │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

### Core Components

| Component | Responsibility |
|-----------|---------------|
| **P2PChatApp** | Entry point, khởi tạo JavaFX |
| **MainController** | Điều phối toàn bộ app lifecycle |
| **AuthManager** | Xác thực user, UI login/register |
| **ChatManager** | UI chat, message display, group management |
| **NetworkManager** | P2P networking, discovery, routing |
| **CallManager** | UI cho voice/video call, accept/reject |
| **VoiceCallManager** | Voice streaming logic |
| **VideoCallManager** | Video + audio streaming logic |
| **PeerInfo** | Lưu thông tin peer (IP, ports) |
| **ChatGroup** | Model cho group chat |

### Network Ports

| Service | Port Range | Description |
|---------|-----------|-------------|
| Discovery | `8889` | Peer discovery server (fixed) |
| Chat Server | `8888-9887` | Nhận kết nối chat từ peers |
| File Server | `8890-9889` | File transfer server |
| Voice Call | `9000-9999` | Voice streaming |
| Video Stream | `9500-10499` | Video data |
| Video Audio | `9600-10599` | Audio cho video call |

**Note**: Ports được tính động: `BASE_PORT + abs(username.hashCode() % 1000)`

---

## 🔌 Protocol Specification

### Message Format

```
MESSAGE_TYPE:PARAM1:PARAM2:...
```

### Message Types

| Type | Format | Description |
|------|--------|-------------|
| `HELLO` | `HELLO:username` | Handshake khi kết nối |
| `MESSAGE` | `MESSAGE:sender:content` | Tin nhắn 1-1 |
| `FILE` | `FILE::filename\|size\|ip\|uniqueName` | Metadata file |
| `GROUP_MESSAGE` | `GROUP_MESSAGE:groupName:sender: msg` | Tin nhắn nhóm |
| `GROUP_SYNC` | `GROUP_SYNC:name:creator:members` | Đồng bộ nhóm |
| `GROUP_FILE` | `GROUP_FILE:group:sender\|file\|...` | File trong nhóm |
| `VOICE_CALL` | `VOICE_CALL:caller` | Khởi tạo voice call |
| `VIDEO_CALL` | `VIDEO_CALL:caller` | Khởi tạo video call |
| `CALL_ACCEPTED` | `CALL_ACCEPTED:username` | Chấp nhận cuộc gọi |
| `CALL_REJECTED` | `CALL_REJECTED:username` | Từ chối cuộc gọi |

### Connection Flow

```
Client A                          Client B
   |                                 |
   |-------- HELLO:alice ----------->|
   |                                 |
   |<------- HELLO:bob --------------|
   |                                 |
   |-- MESSAGE:alice:Hello Bob ----->|
   |                                 |
   |<-- MESSAGE:bob:Hi Alice --------|
   |                                 |
```

### Discovery Flow

```
New Peer (Alice)              Existing Peer (Bob)
   |                                 |
   |-- ANNOUNCE:alice:ports... ----->| (Port 8889)
   |                                 |
   |<-- PEER:bob:ports... -----------|
   |                                 |
[Alice adds Bob to peer list]
[Bob adds Alice to peer list]
```

---

## 📁 Project Structure

```
chat-p2p/
├── src/
│   ├── P2PChatApp.java           # Entry point
│   ├── MainController.java       # Main orchestrator
│   ├── AuthManager.java          # Authentication
│   ├── ChatManager.java          # Chat UI & logic
│   ├── NetworkManager.java       # P2P networking
│   ├── CallManager.java          # Call UI
│   ├── VoiceCallManager.java     # Voice streaming
│   ├── VideoCallManager.java     # Video streaming
│   ├── PeerInfo.java             # Peer data model
│   ├── PeerConnection.java       # Connection wrapper
│   └── ChatGroup.java            # Group model
├── users.txt                     # User credentials
├── chat_history/                 # Chat logs
│   ├── alice_bob.txt
│   └── alice_Team_group.txt
├── groups/                       # Group metadata
│   ├── alice_group_Team.txt
│   └── bob_group_Team.txt
├── shared_files/                 # Shared files
│   └── 1234567_document.pdf
├── pom.xml                       # Maven config
└── README.md                     # This file
```

---

## 🔧 Technical Details

### Threading Model

- **Discovery Threads**: 254 concurrent threads quét subnet
- **Server Threads**:
    - Discovery Server (1 thread)
    - Chat Server (1 thread + N connection threads)
    - File Server (1 thread + N transfer threads)
    - Voice Server (1 thread + N call threads)
    - Video Server (1 thread + N call threads)
- **Call Threads**:
    - Audio Send/Receive (2 threads)
    - Video Send/Receive/Display (3 threads)

### Data Structures

```java
// Thread-safe collections
ConcurrentHashMap<String, PeerConnection> peerConnections;
ConcurrentHashMap<String, PeerInfo> discoveredPeers;
ConcurrentHashMap<String, ChatGroup> chatGroups;

// Atomic state
AtomicBoolean isCallActive;
AtomicBoolean isVideoCallActive;
```

### Video Specs

- **Resolution**: 640x480
- **FPS**: 15
- **Codec**: JPEG compression
- **Local Display**: 200x150 (overlay)
- **Remote Display**: 800x600 (main)

### Audio Specs

- **Sample Rate**: 16 kHz
- **Bit Depth**: 16-bit
- **Channels**: Mono (1)
- **Buffer Size**: 1024 bytes
- **Format**: PCM signed big-endian

---

## 🐛 Troubleshooting

### Peers không tự động hiển thị

**Nguyên nhân**: Firewall block port 8889

**Giải pháp**:
```bash
# Windows
netsh advfirewall firewall add rule name="P2P Chat Discovery" dir=in action=allow protocol=TCP localport=8889

# Linux
sudo ufw allow 8889/tcp

# macOS
# System Preferences > Security & Privacy > Firewall > Firewall Options
```

### Video call không kết nối

**Checklist**:
- ✅ Webcam đã được cấp quyền cho ứng dụng
- ✅ Không có app khác đang sử dụng webcam
- ✅ Firewall cho phép ports 9500-10599
- ✅ Peer đã accept cuộc gọi

### File transfer failed

**Kiểm tra**:
- File size < 50MB
- Thư mục `shared_files/` có quyền write
- Peer còn kết nối (ping IP)

### Voice/Video có tiếng echo

**Giải pháp**:
- Sử dụng headphone
- Giảm volume speaker
- Kiểm tra audio input/output settings của OS

---

## 🤝 Contributing

Contributions are welcome!

### How to Contribute

1. Fork the project
2. Create your feature branch
   ```bash
   git checkout -b feature/AmazingFeature
   ```
3. Commit your changes
   ```bash
   git commit -m 'Add some AmazingFeature'
   ```
4. Push to the branch
   ```bash
   git push origin feature/AmazingFeature
   ```
5. Open a Pull Request

### Coding Guidelines

- Follow Java naming conventions
- Comment code khi cần thiết
- Test trên ít nhất 2 máy khác nhau
- Update README nếu thêm features mới

---

## 📝 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## 👥 Authors

- **Đỗ Quốc Phong** - *Initial work* - [Babychandoi](https://github.com/Babychandoi)

---

## 🙏 Acknowledgments

- [Sarxos Webcam Capture](https://github.com/sarxos/webcam-capture) - Webcam API
- [JavaFX](https://openjfx.io/) - UI Framework
- Inspired by Telegram, Discord, Slack

---


## 🎓 Education Use

Project này được phát triển cho mục đích học tập và nghiên cứu. Free to use cho:
- Đồ án môn học
- Luận văn tốt nghiệp
- Self-study networking & JavaFX
- Teaching materials

**Note**: Nếu sử dụng cho academic purposes, vui lòng credit nguồn.

---

<div align="center">

**[⬆ Back to Top](#-chat-p2p-application)**

Made with ❤️ using Java & JavaFX

⭐ Star this repo if you find it helpful!

</div>