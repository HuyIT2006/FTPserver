package vn.ftpmanager.ftp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.ftpmanager.data.AccountRepository;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FtpClientServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void uploadsAndDownloadsFilesThroughDatabaseAuthenticatedServer() throws Exception {
        AccountRepository repository = new AccountRepository(temporaryDirectory.resolve("database"));
        char[] password = "StrongPass123".toCharArray();
        repository.create("test_user", password);
        repository.create("other_user", "AnotherPass123".toCharArray());

        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        FtpServerService server = new FtpServerService();
        FtpClientService firstClient = new FtpClientService();
        FtpClientService secondClient = new FtpClientService();
        Path sourceFile = temporaryDirectory.resolve("source.txt");
        Path downloadedFile = temporaryDirectory.resolve("downloaded.txt");
        Files.writeString(sourceFile, "FTP transfer integration check");

        try {
            server.start(port, repository);
            firstClient.connect("127.0.0.1", port, "test_user", "StrongPass123");
            secondClient.connect("127.0.0.1", port, "other_user", "AnotherPass123");
            firstClient.upload(sourceFile, "/source.txt");
            secondClient.download("/source.txt", downloadedFile);

            assertEquals(Files.readString(sourceFile), Files.readString(downloadedFile));
        } finally {
            firstClient.disconnect();
            secondClient.disconnect();
            server.stop();
        }
    }
}