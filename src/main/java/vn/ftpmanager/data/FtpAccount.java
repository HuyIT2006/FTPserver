package vn.ftpmanager.data;

import java.nio.file.Path;

public record FtpAccount(String username, Path homeDirectory, boolean enabled) {
}