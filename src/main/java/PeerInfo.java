public class PeerInfo {
    public String ip;
    public int port;
    public int filePort;
    public int voicePort;
    public int videoPort;
    public int videoAudioPort;

    public PeerInfo(String ip, int port, int filePort) {
        this.ip = ip;
        this.port = port;
        this.filePort = filePort;
        this.voicePort = -1;
        this.videoPort = -1;
        this.videoAudioPort = -1;
    }

    public PeerInfo(String ip, int port, int filePort, int voicePort, int videoPort, int videoAudioPort) {
        this.ip = ip;
        this.port = port;
        this.filePort = filePort;
        this.voicePort = voicePort;
        this.videoPort = videoPort;
        this.videoAudioPort = videoAudioPort;
    }

    public String getIp() {
        return ip;
    }

    public int getPort() {
        return port;
    }

    public int getFilePort() {
        return filePort;
    }

    public int getVoicePort() {
        return voicePort;
    }

    public void setVoicePort(int voicePort) {
        this.voicePort = voicePort;
    }

    public int getVideoPort() {
        return videoPort;
    }

    public void setVideoPort(int videoPort) {
        this.videoPort = videoPort;
    }

    public int getVideoAudioPort() {
        return videoAudioPort;
    }

    public void setVideoAudioPort(int videoAudioPort) {
        this.videoAudioPort = videoAudioPort;
    }

    @Override
    public String toString() {
        return "PeerInfo{" +
                "ip='" + ip + '\'' +
                ", port=" + port +
                ", filePort=" + filePort +
                ", voicePort=" + voicePort +
                ", videoPort=" + videoPort +
                ", videoAudioPort=" + videoAudioPort +
                '}';
    }
}