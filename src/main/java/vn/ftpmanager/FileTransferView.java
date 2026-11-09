package vn.ftpmanager;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.apache.commons.net.ftp.FTPFile;
import vn.ftpmanager.ftp.FtpClientService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

public final class FileTransferView extends BorderPane {
    private final FtpClientService ftpClient = new FtpClientService();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ftp-client-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ObservableList<TransferJob> transferJobs = FXCollections.observableArrayList();
    private final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final TextField hostField = new TextField();
    private final Spinner<Integer> portSpinner = new Spinner<>(1, 65535, 2121);
    private final TextField usernameField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final Button connectionButton = new Button("Quickconnect");
    private final Label connectionStatus = new Label("Not connected");
    private final TextField localPathField = new TextField();
    private final TextField remotePathField = new TextField("/");
    private final TableView<LocalEntry> localTable = new TableView<>();
    private final TableView<FTPFile> remoteTable = new TableView<>();
    private final TableView<TransferJob> queueTable = new TableView<>();
    private Path localPath = Path.of(System.getProperty("user.home")).toAbsolutePath();
    private String remotePath = "/";
    private boolean connected;
    private boolean closed;

    public FileTransferView() {
        getStyleClass().add("transfer-view");
        setTop(buildConnectionPanel());
        setCenter(buildBrowserPanel());
        setBottom(buildQueuePanel());
        refreshLocalFiles();
        updateConnectionState(false, "Not connected");
    }

    private VBox buildConnectionPanel() {
        Label title = new Label("Quickconnect");
        title.getStyleClass().add("connection-title");

        hostField.setPromptText("Server LAN IP / hostname");
        usernameField.setPromptText("Username");
        passwordField.setPromptText("Password");
        portSpinner.setEditable(true);
        portSpinner.getStyleClass().add("transfer-input");
        hostField.getStyleClass().add("transfer-input");
        usernameField.getStyleClass().add("transfer-input");
        passwordField.getStyleClass().add("transfer-input");
        hostField.setPrefWidth(190);
        portSpinner.setPrefWidth(92);
        usernameField.setPrefWidth(155);
        passwordField.setPrefWidth(155);

        connectionButton.getStyleClass().add("connect-button");
        connectionButton.setOnAction(event -> toggleConnection());
        hostField.setOnAction(event -> toggleConnection());
        usernameField.setOnAction(event -> toggleConnection());
        passwordField.setOnAction(event -> toggleConnection());

        HBox fields = new HBox(10,
                labeledField("HOST", hostField),
                labeledField("PORT", portSpinner),
                labeledField("USERNAME", usernameField),
                labeledField("PASSWORD", passwordField),
                connectionButton);
        fields.setAlignment(Pos.BOTTOM_LEFT);
        fields.setPadding(new Insets(7, 14, 5, 14));
        HBox.setHgrow(fields.getChildren().get(0), Priority.ALWAYS);
        hostField.setMaxWidth(Double.MAX_VALUE);

        connectionStatus.getStyleClass().add("connection-status");
        HBox statusRow = new HBox(connectionStatus);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusRow.setPadding(new Insets(0, 14, 7, 14));

        VBox panel = new VBox(4, title, fields, statusRow);
        panel.setPadding(new Insets(8, 0, 0, 0));
        panel.getStyleClass().add("connection-panel");
        return panel;
    }

    private VBox labeledField(String labelText, javafx.scene.Node input) {
        Label label = new Label(labelText);
        label.getStyleClass().add("connection-label");
        VBox field = new VBox(4, label, input);
        HBox.setHgrow(field, Priority.ALWAYS);
        return field;
    }

    private SplitPane buildBrowserPanel() {
        VBox localPanel = buildLocalPanel();
        VBox remotePanel = buildRemotePanel();
        SplitPane browsers = new SplitPane(localPanel, remotePanel);
        browsers.setDividerPositions(0.5);
        browsers.getStyleClass().add("browser-split");
        browsers.setPadding(new Insets(7, 8, 6, 8));
        return browsers;
    }

    private VBox buildLocalPanel() {
        Label title = new Label("Local site");
        title.getStyleClass().add("browser-title");
        Button upButton = iconButton("↑", "Parent folder");
        upButton.setOnAction(event -> goToLocalParent());
        Button refreshButton = iconButton("↻", "Refresh local files");
        refreshButton.setOnAction(event -> refreshLocalFiles());
        Button uploadButton = new Button("Upload");
        uploadButton.getStyleClass().add("transfer-action");
        uploadButton.setOnAction(event -> uploadSelected());
        HBox titleRow = new HBox(8, title, spacer(), uploadButton, upButton, refreshButton);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        localPathField.setText(localPath.toString());
        localPathField.getStyleClass().add("path-input");
        localPathField.setOnAction(event -> navigateLocal(localPathField.getText()));
        HBox pathRow = new HBox(localPathField);
        HBox.setHgrow(localPathField, Priority.ALWAYS);

        configureLocalTable();
        VBox.setVgrow(localTable, Priority.ALWAYS);
        VBox panel = new VBox(7, titleRow, pathRow, localTable);
        panel.getStyleClass().add("browser-panel");
        return panel;
    }

    private VBox buildRemotePanel() {
        Label title = new Label("Remote site");
        title.getStyleClass().add("browser-title");
        Button upButton = iconButton("↑", "Parent folder");
        upButton.setOnAction(event -> goToRemoteParent());
        Button refreshButton = iconButton("↻", "Refresh remote files");
        refreshButton.setOnAction(event -> refreshRemoteFiles());
        Button downloadButton = new Button("Download");
        downloadButton.getStyleClass().add("transfer-action");
        downloadButton.setOnAction(event -> downloadSelected());
        HBox titleRow = new HBox(8, title, spacer(), downloadButton, upButton, refreshButton);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        remotePathField.getStyleClass().add("path-input");
        remotePathField.setOnAction(event -> navigateRemote(remotePathField.getText()));
        HBox pathRow = new HBox(remotePathField);
        HBox.setHgrow(remotePathField, Priority.ALWAYS);

        configureRemoteTable();
        VBox.setVgrow(remoteTable, Priority.ALWAYS);
        VBox panel = new VBox(7, titleRow, pathRow, remoteTable);
        panel.getStyleClass().add("browser-panel");
        return panel;
    }

    private void configureLocalTable() {
        localTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        localTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        localTable.setPlaceholder(new Label("This folder is empty"));
        TableColumn<LocalEntry, String> name = new TableColumn<>("Name");
        name.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().name()));
        TableColumn<LocalEntry, String> size = new TableColumn<>("Size");
        size.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().directory() ? "Folder" : formatSize(cell.getValue().size())));
        TableColumn<LocalEntry, String> modified = new TableColumn<>("Modified");
        modified.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().modified() == null ? "" : dateFormat.format(cell.getValue().modified())));
        localTable.getColumns().addAll(name, size, modified);
        localTable.getStyleClass().add("file-table");
        localTable.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                LocalEntry selected = localTable.getSelectionModel().getSelectedItem();
                if (selected != null && selected.directory()) {
                    navigateLocal(selected.path().toString());
                }
            }
        });
    }

    private void configureRemoteTable() {
        remoteTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        remoteTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        remoteTable.setPlaceholder(new Label("Connect to an FTP server to browse files"));
        TableColumn<FTPFile, String> name = new TableColumn<>("Name");
        name.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().isDirectory() ? cell.getValue().getName() + "/" : cell.getValue().getName()));
        TableColumn<FTPFile, String> size = new TableColumn<>("Size");
        size.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().isDirectory() ? "Folder" : formatSize(cell.getValue().getSize())));
        TableColumn<FTPFile, String> modified = new TableColumn<>("Modified");
        modified.setCellValueFactory(cell -> new SimpleStringProperty(formatRemoteDate(cell.getValue())));
        remoteTable.getColumns().addAll(name, size, modified);
        remoteTable.getStyleClass().add("file-table");
        remoteTable.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                FTPFile selected = remoteTable.getSelectionModel().getSelectedItem();
                if (selected != null && selected.isDirectory()) {
                    navigateRemote(joinRemote(remotePath, selected.getName()));
                }
            }
        });
    }

    private VBox buildQueuePanel() {
        Label title = new Label("Transfer queue");
        title.getStyleClass().add("browser-title");
        Button clearButton = new Button("Clear completed");
        clearButton.getStyleClass().add("quiet-button");
        clearButton.setOnAction(event -> transferJobs.removeIf(job ->
                job.status().equals("Complete") || job.status().startsWith("Failed")));
        HBox heading = new HBox(title, spacer(), clearButton);
        heading.setAlignment(Pos.CENTER_LEFT);

        queueTable.setItems(transferJobs);
        queueTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        queueTable.setPlaceholder(new Label("Transfers will appear here"));
        queueTable.setPrefHeight(110);
        queueTable.setMinHeight(90);
        TableColumn<TransferJob, String> file = new TableColumn<>("File");
        file.setCellValueFactory(new PropertyValueFactory<>("file"));
        TableColumn<TransferJob, String> direction = new TableColumn<>("Direction");
        direction.setCellValueFactory(new PropertyValueFactory<>("direction"));
        TableColumn<TransferJob, String> size = new TableColumn<>("Size");
        size.setCellValueFactory(new PropertyValueFactory<>("size"));
        TableColumn<TransferJob, String> status = new TableColumn<>("Status");
        status.setCellValueFactory(new PropertyValueFactory<>("status"));
        queueTable.getColumns().addAll(file, direction, size, status);
        queueTable.getStyleClass().add("file-table");

        VBox panel = new VBox(8, heading, queueTable);
        panel.setPadding(new Insets(6, 12, 10, 12));
        panel.getStyleClass().add("queue-panel");
        return panel;
    }

    private void toggleConnection() {
        if (connected) {
            disconnect();
            return;
        }
        String host = hostField.getText().trim();
        String username = usernameField.getText().trim();
        String password = passwordField.getText();
        if (host.isBlank() || username.isBlank() || password.isBlank()) {
            setConnectionMessage("Enter the server address, username, and password");
            return;
        }
        int port = portSpinner.getValue();
        connectionButton.setDisable(true);
        setConnectionMessage("Connecting to " + host + "...");
        worker.execute(() -> {
            try {
                String path = ftpClient.connect(host, port, username, password);
                FTPFile[] files = ftpClient.listRemoteFiles();
                Platform.runLater(() -> {
                    remotePath = path;
                    remotePathField.setText(path);
                    showRemoteFiles(files);
                    updateConnectionState(true, "Connected to " + host + ":" + port);
                });
            } catch (Exception exception) {
                try {
                    ftpClient.disconnect();
                } catch (IOException ignored) {
                }
                Platform.runLater(() -> updateConnectionState(false, message(exception)));
            } finally {
                Platform.runLater(() -> connectionButton.setDisable(false));
            }
        });
    }

    private void disconnect() {
        connectionButton.setDisable(true);
        worker.execute(() -> {
            try {
                ftpClient.disconnect();
                Platform.runLater(() -> {
                    remoteTable.getItems().clear();
                    remotePath = "/";
                    remotePathField.setText(remotePath);
                    updateConnectionState(false, "Disconnected");
                });
            } catch (Exception exception) {
                Platform.runLater(() -> updateConnectionState(false, message(exception)));
            } finally {
                Platform.runLater(() -> connectionButton.setDisable(false));
            }
        });
    }

    private void updateConnectionState(boolean isConnected, String message) {
        connected = isConnected;
        connectionButton.setText(isConnected ? "Disconnect" : "Quickconnect");
        connectionButton.getStyleClass().setAll(isConnected ? "disconnect-button" : "connect-button");
        hostField.setDisable(isConnected);
        portSpinner.setDisable(isConnected);
        usernameField.setDisable(isConnected);
        passwordField.setDisable(isConnected);
        remotePathField.setDisable(!isConnected);
        connectionStatus.setText(message);
        connectionStatus.getStyleClass().setAll("connection-status",
                isConnected ? "connected" : "disconnected");
        if (!isConnected && message.equals("Disconnected")) {
            passwordField.clear();
        }
    }

    private void setConnectionMessage(String message) {
        connectionStatus.setText(message);
        connectionStatus.getStyleClass().setAll("connection-status", connected ? "connected" : "connecting");
    }

    private void navigateLocal(String requestedPath) {
        try {
            Path next = Path.of(requestedPath).toAbsolutePath().normalize();
            if (!Files.isDirectory(next)) {
                setConnectionMessage("Local folder does not exist: " + next);
                return;
            }
            localPath = next;
            localPathField.setText(localPath.toString());
            refreshLocalFiles();
        } catch (Exception exception) {
            setConnectionMessage(message(exception));
        }
    }

    private void goToLocalParent() {
        Path parent = localPath.getParent();
        if (parent != null) {
            navigateLocal(parent.toString());
        }
    }

    private void refreshLocalFiles() {
        localPathField.setText(localPath.toString());
        try (Stream<Path> paths = Files.list(localPath)) {
            List<LocalEntry> entries = paths.map(FileTransferView::toLocalEntry)
                    .sorted(Comparator.comparing(LocalEntry::directory).reversed()
                            .thenComparing(entry -> entry.name().toLowerCase()))
                    .toList();
            localTable.setItems(FXCollections.observableArrayList(entries));
        } catch (IOException exception) {
            setConnectionMessage("Cannot list local folder: " + message(exception));
        }
    }

    private static LocalEntry toLocalEntry(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            Path fileName = path.getFileName();
            return new LocalEntry(path, fileName == null ? path.toString() : fileName.toString(),
                    attributes.isDirectory(), attributes.size(),
                    LocalDateTime.ofInstant(attributes.lastModifiedTime().toInstant(), ZoneId.systemDefault()));
        } catch (IOException exception) {
            Path fileName = path.getFileName();
            return new LocalEntry(path, fileName == null ? path.toString() : fileName.toString(),
                    Files.isDirectory(path), 0, null);
        }
    }

    private void navigateRemote(String requestedPath) {
        if (!connected) {
            setConnectionMessage("Connect to an FTP server first");
            return;
        }
        String path = requestedPath == null || requestedPath.isBlank() ? "/" : requestedPath.trim();
        worker.execute(() -> {
            try {
                FTPFile[] files = ftpClient.changeDirectory(path);
                String actualPath = ftpClient.getWorkingDirectory();
                Platform.runLater(() -> {
                    remotePath = actualPath;
                    remotePathField.setText(actualPath);
                    showRemoteFiles(files);
                    setConnectionMessage("Remote folder: " + actualPath);
                });
            } catch (Exception exception) {
                Platform.runLater(() -> setConnectionMessage(message(exception)));
            }
        });
    }

    private void goToRemoteParent() {
        if (!connected || remotePath.equals("/")) {
            return;
        }
        int separator = remotePath.lastIndexOf('/');
        navigateRemote(separator <= 0 ? "/" : remotePath.substring(0, separator));
    }

    private void refreshRemoteFiles() {
        if (!connected) {
            return;
        }
        worker.execute(() -> {
            try {
                FTPFile[] files = ftpClient.listRemoteFiles();
                Platform.runLater(() -> showRemoteFiles(files));
            } catch (Exception exception) {
                Platform.runLater(() -> setConnectionMessage(message(exception)));
            }
        });
    }

    private void showRemoteFiles(FTPFile[] files) {
        List<FTPFile> visibleFiles = new ArrayList<>();
        for (FTPFile file : files) {
            if (!file.getName().equals(".") && !file.getName().equals("..")) {
                visibleFiles.add(file);
            }
        }
        visibleFiles.sort(Comparator.comparing(FTPFile::isDirectory).reversed()
                .thenComparing(file -> file.getName().toLowerCase()));
        remoteTable.setItems(FXCollections.observableArrayList(visibleFiles));
    }

    private void uploadSelected() {
        if (!connected) {
            setConnectionMessage("Connect to an FTP server before uploading");
            return;
        }
        List<LocalEntry> selected = localTable.getSelectionModel().getSelectedItems().stream()
                .filter(entry -> !entry.directory()).toList();
        if (selected.isEmpty()) {
            setConnectionMessage("Select one or more local files to upload");
            return;
        }
        List<TransferJob> jobs = new ArrayList<>();
        for (LocalEntry entry : selected) {
            TransferJob job = new TransferJob(entry.name(), "Upload", formatSize(entry.size()), "Queued");
            jobs.add(job);
            transferJobs.add(job);
        }
        String destination = remotePath;
        worker.execute(() -> {
            for (int index = 0; index < selected.size(); index++) {
                LocalEntry entry = selected.get(index);
                TransferJob job = jobs.get(index);
                updateJob(job, "Transferring");
                try {
                    ftpClient.upload(entry.path(), joinRemote(destination, entry.name()));
                    updateJob(job, "Complete");
                } catch (Exception exception) {
                    updateJob(job, "Failed: " + message(exception));
                }
            }
            try {
                FTPFile[] files = ftpClient.listRemoteFiles();
                Platform.runLater(() -> showRemoteFiles(files));
            } catch (Exception exception) {
                Platform.runLater(() -> setConnectionMessage(message(exception)));
            }
        });
    }

    private void downloadSelected() {
        if (!connected) {
            setConnectionMessage("Connect to an FTP server before downloading");
            return;
        }
        List<FTPFile> selected = remoteTable.getSelectionModel().getSelectedItems().stream()
                .filter(file -> !file.isDirectory()).toList();
        if (selected.isEmpty()) {
            setConnectionMessage("Select one or more remote files to download");
            return;
        }
        List<TransferJob> jobs = new ArrayList<>();
        for (FTPFile file : selected) {
            TransferJob job = new TransferJob(file.getName(), "Download", formatSize(file.getSize()), "Queued");
            jobs.add(job);
            transferJobs.add(job);
        }
        String source = remotePath;
        Path destination = localPath;
        worker.execute(() -> {
            for (int index = 0; index < selected.size(); index++) {
                FTPFile file = selected.get(index);
                TransferJob job = jobs.get(index);
                Path localFile = destination.resolve(file.getName());
                updateJob(job, "Transferring");
                try {
                    if (Files.exists(localFile)) {
                        throw new IOException("Local file already exists");
                    }
                    ftpClient.download(joinRemote(source, file.getName()), localFile);
                    updateJob(job, "Complete");
                } catch (Exception exception) {
                    updateJob(job, "Failed: " + message(exception));
                }
            }
            Platform.runLater(this::refreshLocalFiles);
        });
    }

    private void updateJob(TransferJob job, String status) {
        Platform.runLater(() -> job.setStatus(status));
    }

    private Button iconButton(String symbol, String description) {
        Button button = new Button(symbol);
        button.getStyleClass().add("icon-button");
        button.setTooltip(new Tooltip(description));
        return button;
    }

    private Label spacer() {
        Label spacer = new Label();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private static String joinRemote(String directory, String name) {
        if (directory == null || directory.isBlank() || directory.equals("/")) {
            return "/" + name;
        }
        return directory.endsWith("/") ? directory + name : directory + "/" + name;
    }

    private static String formatSize(long size) {
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        }
        if (size < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", size / (1024.0 * 1024));
        }
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    private String formatRemoteDate(FTPFile file) {
        if (file.getTimestamp() == null) {
            return "";
        }
        Instant instant = file.getTimestamp().toInstant();
        return dateFormat.format(LocalDateTime.ofInstant(instant, ZoneId.systemDefault()));
    }

    private static String message(Exception exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value;
    }

    public void shutdown() {
        if (closed) {
            return;
        }
        closed = true;
        worker.execute(() -> {
            try {
                ftpClient.disconnect();
            } catch (IOException ignored) {
            }
        });
        worker.shutdown();
    }

    private record LocalEntry(Path path, String name, boolean directory, long size, LocalDateTime modified) {
    }

    public static final class TransferJob {
        private final StringProperty file = new SimpleStringProperty();
        private final StringProperty direction = new SimpleStringProperty();
        private final StringProperty size = new SimpleStringProperty();
        private final StringProperty status = new SimpleStringProperty();

        private TransferJob(String file, String direction, String size, String status) {
            this.file.set(file);
            this.direction.set(direction);
            this.size.set(size);
            this.status.set(status);
        }

        public String getFile() {
            return file.get();
        }

        public String getDirection() {
            return direction.get();
        }

        public String getSize() {
            return size.get();
        }

        public String getStatus() {
            return status.get();
        }

        public StringProperty fileProperty() {
            return file;
        }

        public StringProperty directionProperty() {
            return direction;
        }

        public StringProperty sizeProperty() {
            return size;
        }

        public StringProperty statusProperty() {
            return status;
        }

        public String status() {
            return status.get();
        }

        private void setStatus(String value) {
            status.set(value);
        }
    }
}