package vn.ftpmanager.ftp;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FtpClientService {
    private FTPClient client;

    public String connect(String host, int port, String username, String password) throws IOException {
        disconnect();
        FTPClient newClient = new FTPClient();
        newClient.setConnectTimeout(5000);
        newClient.setDataTimeout(15_000);
        newClient.setControlEncoding("UTF-8");
        newClient.setUseEPSVwithIPv4(false);
        newClient.enterLocalPassiveMode();
        newClient.connect(host, port);
        if (!FTPReply.isPositiveCompletion(newClient.getReplyCode())) {
            String reply = newClient.getReplyString();
            newClient.disconnect();
            throw new IOException("FTP server rejected the connection: " + reply);
        }
        if (!newClient.login(username, password)) {
            String reply = newClient.getReplyString();
            newClient.disconnect();
            throw new IOException("FTP login failed: " + reply);
        }
        newClient.setFileType(FTP.BINARY_FILE_TYPE);
        client = newClient;
        String workingDirectory = client.printWorkingDirectory();
        return workingDirectory == null ? "/" : workingDirectory;
    }

    public FTPFile[] changeDirectory(String path) throws IOException {
        requireConnection();
        if (!client.changeWorkingDirectory(path)) {
            throw new IOException("Cannot open remote folder: " + client.getReplyString());
        }
        return client.listFiles();
    }

    public FTPFile[] listRemoteFiles() throws IOException {
        requireConnection();
        return client.listFiles();
    }

    public String getWorkingDirectory() throws IOException {
        requireConnection();
        String path = client.printWorkingDirectory();
        return path == null ? "/" : path;
    }

    public void upload(Path localFile, String remoteFile) throws IOException {
        requireConnection();
        try (InputStream input = Files.newInputStream(localFile)) {
            if (!client.storeFile(remoteFile, input)) {
                throw new IOException("Upload failed: " + client.getReplyString());
            }
        }
    }

    public void download(String remoteFile, Path localFile) throws IOException {
        requireConnection();
        try (OutputStream output = Files.newOutputStream(localFile)) {
            if (!client.retrieveFile(remoteFile, output)) {
                throw new IOException("Download failed: " + client.getReplyString());
            }
        }
    }

    public void disconnect() throws IOException {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                try {
                    client.logout();
                } finally {
                    client.disconnect();
                }
            }
        } finally {
            client = null;
        }
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    private void requireConnection() throws IOException {
        if (!isConnected()) {
            throw new IOException("Not connected to an FTP server");
        }
    }
}