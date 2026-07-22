package de.schrell.quickdiskscan;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyLongWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;

import static de.schrell.quickdiskscan.I18n.numberLocale;
import static de.schrell.quickdiskscan.I18n.text;

public final class QuickDiskScanApp extends Application {
    private static final NumberFormat NUMBER = NumberFormat.getIntegerInstance(numberLocale());
    private static final String PREF_PATH = "scanPath";
    private static final String PREF_PARALLELISM = "parallelism";
    private static final String PREF_SIZE_BASIS = "sizeBasis";

    private final Preferences preferences = Preferences.userNodeForPackage(QuickDiskScanApp.class);
    private final ComboBox<VolumeDiscovery.Volume> volumeBox = new ComboBox<>();
    private final TextField pathField = new TextField();
    private final Button chooseButton = new Button(text("Auswählen …", "Choose …"));
    private final Spinner<Integer> parallelSpinner = new Spinner<>(1, 64,
            Math.min(16, Math.max(2, Runtime.getRuntime().availableProcessors())));
    private final ToggleButton logicalButton = new ToggleButton(text("Virtuelle Größe", "Logical size"));
    private final ToggleButton physicalButton = new ToggleButton(text("Belegte Blöcke", "Allocated blocks"));
    private final Button scanButton = new Button(text("Scannen", "Scan"));
    private final Button cancelButton = new Button(text("Abbrechen", "Cancel"));
    private final ProgressBar volumeBar = new ProgressBar();
    private final Label volumeLabel = new Label(text("Volumes werden ermittelt …", "Discovering volumes …"));
    private final SunburstView sunburst = new SunburstView();
    private final Button parentButton = new Button("↑");
    private final Label breadcrumb = new Label(text("Noch kein Scan", "No scan yet"));
    private final ObservableList<DiskScanner.ScanNode> visibleNodes = FXCollections.observableArrayList();
    private final SortedList<DiskScanner.ScanNode> sortedNodes = new SortedList<>(visibleNodes);
    private final TableView<DiskScanner.ScanNode> table = new TableView<>(sortedNodes);
    private final TableColumn<DiskScanner.ScanNode, Long> sizeColumn = new TableColumn<>(text("Physisch", "Physical"));
    private final TableColumn<DiskScanner.ScanNode, Long> shareColumn = new TableColumn<>(text("Anteil", "Share"));
    private final Button revealButton = new Button(text("Im Dateimanager zeigen", "Reveal in file manager"));
    private final Button deleteButton = new Button(text("Löschen …", "Delete …"));
    private final ProgressIndicator deleteProgress = new ProgressIndicator();
    private final ProgressBar progressBar = new ProgressBar();
    private final Label progressLabel = new Label(text("Bereit", "Ready"));
    private final Label statisticsLabel = new Label(text("Noch kein Scan ausgeführt.", "No scan has run yet."));
    private final Timeline refreshTimer = new Timeline(new KeyFrame(Duration.millis(250), event -> refresh(false)));
    private final Timeline volumeRefreshTimer = new Timeline(
            new KeyFrame(Duration.seconds(3), event -> refreshVolumesAsync()));
    private final AtomicBoolean discoveringVolumes = new AtomicBoolean();

    private Stage stage;
    private volatile DiskScanner scanner;
    private volatile Thread scanThread;
    private DiskScanner.ScanNode focusedNode;
    private DiskScanner.SizeBasis sizeBasis = DiskScanner.SizeBasis.PHYSICAL;
    private long expectedVolumeBytes;
    private long lastDisplayedEntries = -1;
    private boolean busy;
    private boolean deleting;

    public QuickDiskScanApp() {}

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        stage.setTitle("QuickDiskScan");
        try (InputStream icon = QuickDiskScanApp.class.getResourceAsStream("/de/schrell/quickdiskscan/icon.png")) {
            if (icon != null) {
                stage.getIcons().add(new Image(icon));
            }
        } catch (IOException ignored) {
            // The packaged application still has its platform icon.
        }

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setPadding(new Insets(18));
        root.setTop(createHeader());
        root.setCenter(createContent());
        root.setBottom(createFooter());

        Scene scene = new Scene(root, 1160, 760);
        var stylesheet = QuickDiskScanApp.class.getResource("/de/schrell/quickdiskscan/app.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE && busy) {
                cancelScan();
            } else if (event.getCode() == KeyCode.DELETE && !table.getSelectionModel().isEmpty()) {
                confirmDelete(new ArrayList<>(table.getSelectionModel().getSelectedItems()));
            }
        });
        stage.setScene(scene);
        stage.setMinWidth(820);
        stage.setMinHeight(560);
        stage.setOnCloseRequest(event -> cancelCurrentWork());

        restoreSettings();
        List<String> arguments = getParameters().getRaw();
        boolean scanArgument = !arguments.isEmpty();
        if (scanArgument) {
            pathField.setText(arguments.getFirst());
        }
        configureActions();
        updateBusyState();
        stage.show();

        refreshTimer.setCycleCount(Timeline.INDEFINITE);
        refreshTimer.play();
        volumeRefreshTimer.setCycleCount(Timeline.INDEFINITE);
        volumeRefreshTimer.play();
        if (scanArgument) {
            Platform.runLater(this::startScan);
        }
        refreshVolumesAsync();
    }

    private VBox createHeader() {
        Label title = new Label(text("Speicherbelegung analysieren", "Analyze disk usage"));
        title.getStyleClass().add("title");
        Label subtitle = new Label(text(
                "Paralleler Metadaten-Scan ohne Dateiinhalt – Cloud-Platzhalter bleiben offline.",
                "Parallel metadata scan without reading file data – cloud placeholders stay offline."));
        subtitle.getStyleClass().add("subtitle");

        volumeBox.setMaxWidth(Double.MAX_VALUE);
        pathField.setPromptText(text("Volume oder Verzeichnis auswählen …", "Choose a volume or directory …"));
        GridPane paths = new GridPane();
        paths.setHgap(10);
        paths.setVgap(9);
        paths.getColumnConstraints().addAll(new ColumnConstraints(), growColumn(), new ColumnConstraints());
        paths.add(new Label(text("Volume", "Volume")), 0, 0);
        paths.add(volumeBox, 1, 0, 2, 1);
        paths.add(new Label(text("Pfad", "Path")), 0, 1);
        paths.add(pathField, 1, 1);
        paths.add(chooseButton, 2, 1);

        ToggleGroup sizeGroup = new ToggleGroup();
        logicalButton.setToggleGroup(sizeGroup);
        physicalButton.setToggleGroup(sizeGroup);
        physicalButton.setSelected(true);
        sizeGroup.selectedToggleProperty().addListener((observable, previous, selected) -> {
            if (selected == null) {
                previous.setSelected(true);
                return;
            }
            sizeBasis = selected == logicalButton
                    ? DiskScanner.SizeBasis.LOGICAL : DiskScanner.SizeBasis.PHYSICAL;
            preferences.put(PREF_SIZE_BASIS, sizeBasis.name());
            sizeColumn.setText(sizeBasis == DiskScanner.SizeBasis.LOGICAL
                    ? text("Logisch", "Logical") : text("Physisch", "Physical"));
            lastDisplayedEntries = -1;
            refresh(true);
        });

        parallelSpinner.setEditable(true);
        parallelSpinner.setPrefWidth(88);
        HBox options = new HBox(9, new Label(text("Parallelität", "Parallelism")), parallelSpinner,
                new Label(text("Anzeige", "Display")), logicalButton, physicalButton, spacer(), scanButton, cancelButton);
        options.setAlignment(Pos.CENTER_LEFT);
        scanButton.setDefaultButton(true);
        scanButton.getStyleClass().add("primary-button");
        cancelButton.setCancelButton(true);

        volumeBar.setMaxWidth(Double.MAX_VALUE);
        volumeLabel.getStyleClass().add("subtitle");
        VBox volumeUsage = new VBox(4, volumeBar, volumeLabel);
        VBox box = new VBox(6, title, subtitle, paths, options, volumeUsage);
        VBox.setMargin(paths, new Insets(10, 0, 3, 0));
        VBox.setMargin(volumeUsage, new Insets(2, 0, 12, 0));
        return box;
    }

    private SplitPane createContent() {
        parentButton.setTooltip(new Tooltip(text("Eine Ebene nach oben", "Go up one level")));
        parentButton.setMinWidth(38);
        breadcrumb.setMaxWidth(Double.MAX_VALUE);
        breadcrumb.getStyleClass().add("breadcrumb");
        HBox navigation = new HBox(8, parentButton, breadcrumb);
        navigation.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(breadcrumb, Priority.ALWAYS);
        VBox chartPane = new VBox(8, navigation, sunburst);
        VBox.setVgrow(sunburst, Priority.ALWAYS);
        chartPane.setPadding(new Insets(0, 10, 0, 0));

        configureTable();
        deleteProgress.setMaxSize(48, 48);
        deleteProgress.setVisible(false);
        StackPane tableStack = new StackPane(table, deleteProgress);
        HBox actions = new HBox(8, revealButton, deleteButton);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox tablePane = new VBox(8, tableStack, actions);
        VBox.setVgrow(tableStack, Priority.ALWAYS);
        tablePane.setPadding(new Insets(0, 0, 10, 10));

        SplitPane splitPane = new SplitPane(chartPane, tablePane);
        splitPane.setDividerPositions(0.57);
        return splitPane;
    }

    private void configureTable() {
        TableColumn<DiskScanner.ScanNode, String> nameColumn = new TableColumn<>(text("Name", "Name"));
        nameColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().name()));
        nameColumn.setPrefWidth(270);

        sizeColumn.setCellValueFactory(cell -> new ReadOnlyLongWrapper(sizeBasis.bytes(cell.getValue())).asObject());
        sizeColumn.setCellFactory(ignored -> byteCell());
        sizeColumn.setPrefWidth(115);
        sizeColumn.setSortType(TableColumn.SortType.DESCENDING);

        shareColumn.setCellValueFactory(cell -> new ReadOnlyLongWrapper(sizeBasis.bytes(cell.getValue())).asObject());
        shareColumn.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(Long bytes, boolean empty) {
                super.updateItem(bytes, empty);
                long total = focusedNode == null ? 0 : sizeBasis.bytes(focusedNode);
                setText(empty || bytes == null || total == 0 ? "" : String.format(numberLocale(), "%.1f %%",
                        bytes * 100.0 / total));
                setAlignment(Pos.CENTER_RIGHT);
            }
        });
        shareColumn.setPrefWidth(78);

        TableColumn<DiskScanner.ScanNode, String> statusColumn = new TableColumn<>(text("Status", "Status"));
        statusColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(status(cell.getValue())));
        statusColumn.setPrefWidth(110);
        statusColumn.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                setText(empty ? "" : status);
                setStyle(!empty && status != null && status.contains("Offline")
                        ? "-fx-text-fill: #d44; -fx-font-weight: bold;" : "");
                setAlignment(Pos.CENTER);
            }
        });

        table.getColumns().setAll(List.of(nameColumn, sizeColumn, shareColumn, statusColumn));
        sortedNodes.comparatorProperty().bind(table.comparatorProperty());
        table.getSortOrder().setAll(List.of(sizeColumn));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(text("Scan starten, um die Belegung anzuzeigen",
                "Start a scan to display disk usage")));
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<DiskScanner.ScanNode>) change -> updateActionButtons());
        table.setRowFactory(ignored -> createRow());
    }

    private TableCell<DiskScanner.ScanNode, Long> byteCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(Long bytes, boolean empty) {
                super.updateItem(bytes, empty);
                setText(empty || bytes == null ? "" : ByteFormat.bytes(bytes));
                setAlignment(Pos.CENTER_RIGHT);
            }
        };
    }

    private TableRow<DiskScanner.ScanNode> createRow() {
        TableRow<DiskScanner.ScanNode> row = new TableRow<>();
        MenuItem reveal = new MenuItem(text("Im Dateimanager zeigen", "Reveal in file manager"));
        reveal.setOnAction(event -> reveal(table.getSelectionModel().getSelectedItem()));
        MenuItem delete = new MenuItem(text("Löschen …", "Delete …"));
        delete.setOnAction(event -> {
            List<DiskScanner.ScanNode> selection = List.copyOf(table.getSelectionModel().getSelectedItems());
            Platform.runLater(() -> confirmDelete(selection));
        });
        ContextMenu menu = new ContextMenu(reveal, delete);
        row.emptyProperty().addListener((observable, wasEmpty, isEmpty) -> row.setContextMenu(isEmpty ? null : menu));
        row.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
            if (!row.isEmpty() && !row.isSelected()) {
                table.getSelectionModel().clearAndSelect(row.getIndex());
            }
        });
        row.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && !row.isEmpty()) {
                DiskScanner.ScanNode node = row.getItem();
                if (node.directory() && !node.children().isEmpty()) {
                    focus(node);
                } else {
                    reveal(node);
                }
            }
        });
        return row;
    }

    private VBox createFooter() {
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        progressLabel.getStyleClass().add("progress-label");
        statisticsLabel.getStyleClass().add("subtitle");
        statisticsLabel.setWrapText(true);
        VBox footer = new VBox(5, progressBar, progressLabel, statisticsLabel);
        footer.setPadding(new Insets(12, 0, 0, 0));
        return footer;
    }

    private void configureActions() {
        chooseButton.setOnAction(event -> chooseDirectory());
        scanButton.setOnAction(event -> startScan());
        cancelButton.setOnAction(event -> cancelScan());
        parentButton.setOnAction(event -> {
            if (focusedNode != null && focusedNode.parent() != null) {
                focus(focusedNode.parent());
            }
        });
        sunburst.setOnFocusChanged(this::focusFromChart);
        revealButton.setOnAction(event -> reveal(table.getSelectionModel().getSelectedItem()));
        deleteButton.setOnAction(event -> confirmDelete(
                new ArrayList<>(table.getSelectionModel().getSelectedItems())));
        volumeBox.valueProperty().addListener((observable, previous, selected) -> {
            if (selected != null) {
                pathField.setText(selected.path().toString());
                updateVolumeUsage(selected);
            }
        });
    }

    private void setVolumes(List<VolumeDiscovery.Volume> volumes) {
        volumeBox.getItems().setAll(volumes);
        String restoredPath = pathField.getText();
        VolumeDiscovery.Volume selected = volumes.stream()
                .filter(volume -> volume.path().toString().equals(restoredPath)).findFirst()
                .orElse(volumes.isEmpty() ? null : volumes.getFirst());
        if (selected != null) {
            volumeBox.setValue(selected);
            if (!restoredPath.isBlank()) {
                pathField.setText(restoredPath);
            }
        } else {
            volumeLabel.setText(text("Keine Volumes gefunden – ein Verzeichnis kann direkt gewählt werden.",
                    "No volumes found – a directory can still be selected directly."));
            volumeBar.setProgress(0);
        }
    }

    private void refreshVolumesAsync() {
        if (!discoveringVolumes.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("volume-discovery").start(() -> {
            List<VolumeDiscovery.Volume> volumes = VolumeDiscovery.discover();
            Platform.runLater(() -> {
                try {
                    setVolumes(volumes);
                } finally {
                    discoveringVolumes.set(false);
                }
            });
        });
    }

    private void updateVolumeUsage(VolumeDiscovery.Volume volume) {
        volumeBar.setProgress(volume.usedFraction());
        volumeLabel.setText(ByteFormat.bytes(volume.usedBytes()) + text(" von ", " of ")
                + ByteFormat.bytes(volume.totalBytes()) + text(" belegt · ", " used · ")
                + ByteFormat.bytes(volume.usableBytes()) + text(" frei · ", " free · ") + volume.type());
    }

    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(text("Verzeichnis scannen", "Scan directory"));
        try {
            Path current = Path.of(pathField.getText());
            if (Files.isDirectory(current)) {
                chooser.setInitialDirectory(current.toFile());
            }
        } catch (RuntimeException ignored) {
            // The chooser simply starts at its default directory.
        }
        var selected = chooser.showDialog(stage);
        if (selected != null) {
            pathField.setText(selected.toPath().toAbsolutePath().normalize().toString());
        }
    }

    private void startScan() {
        Path path;
        try {
            path = Path.of(pathField.getText()).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            showError(text("Ungültiger Pfad", "Invalid path"), exception.getMessage());
            return;
        }
        if (!Files.isDirectory(path)) {
            showError(text("Verzeichnis nicht gefunden", "Directory not found"), path.toString());
            return;
        }
        cancelCurrentWork();

        int parallelism = parallelSpinner.getValue();
        preferences.put(PREF_PATH, path.toString());
        preferences.putInt(PREF_PARALLELISM, parallelism);
        expectedVolumeBytes = volumeBox.getItems().stream()
                .filter(volume -> volume.path().equals(path)).mapToLong(VolumeDiscovery.Volume::usedBytes)
                .findFirst().orElse(0);

        DiskScanner nextScanner = new DiskScanner(new DiskScanner.Config(path, parallelism));
        scanner = nextScanner;
        focusedNode = null;
        visibleNodes.clear();
        lastDisplayedEntries = -1;
        busy = true;
        progressLabel.setText(text("Scan wird gestartet …", "Starting scan …"));
        statisticsLabel.setText(text("Metadaten werden mit ", "Reading metadata with ") + NUMBER.format(parallelism)
                + text(" parallelen Workern.", " parallel workers."));
        updateBusyState();

        scanThread = Thread.ofVirtual().name("disk-scan").start(() -> {
            try {
                DiskScanner.Result result = nextScanner.scan();
                Platform.runLater(() -> finishScan(nextScanner, result));
            } catch (CancellationException ignored) {
                Platform.runLater(() -> finishCancelled(nextScanner));
            } catch (Throwable exception) {
                Platform.runLater(() -> finishFailed(nextScanner, exception));
            }
        });
    }

    private void cancelScan() {
        DiskScanner active = scanner;
        if (active != null) {
            active.cancel();
        }
        Thread thread = scanThread;
        if (thread != null) {
            thread.interrupt();
        }
        progressLabel.setText(text("Scan wird abgebrochen …", "Cancelling scan …"));
    }

    private void cancelCurrentWork() {
        if (busy) {
            cancelScan();
        }
    }

    private void finishScan(DiskScanner completedScanner, DiskScanner.Result result) {
        if (scanner != completedScanner) {
            return;
        }
        busy = false;
        scanThread = null;
        refresh(true);
        progressBar.setProgress(1);
        progressLabel.setText(text("Fertig · ", "Done · ") + NUMBER.format(result.snapshot().entries())
                + text(" Einträge in ", " entries in ")
                + formatDuration(result.snapshot().elapsedMillis()));
        updateBusyState();
    }

    private void finishCancelled(DiskScanner cancelledScanner) {
        if (scanner != cancelledScanner) {
            return;
        }
        busy = false;
        scanThread = null;
        refresh(true);
        progressLabel.setText(text("Scan abgebrochen", "Scan cancelled"));
        updateBusyState();
    }

    private void finishFailed(DiskScanner failedScanner, Throwable exception) {
        if (scanner != failedScanner) {
            return;
        }
        busy = false;
        scanThread = null;
        updateBusyState();
        showError(text("Scan fehlgeschlagen", "Scan failed"), exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage());
    }

    private void refresh(boolean force) {
        DiskScanner active = scanner;
        if (active == null) {
            return;
        }
        DiskScanner.Snapshot snapshot = active.snapshot();
        DiskScanner.ScanNode scanRoot = active.root();
        if (scanRoot != null) {
            sunburst.setData(scanRoot, sizeBasis);
            if (focusedNode == null) {
                focusedNode = scanRoot;
            }
            if (force || snapshot.entries() != lastDisplayedEntries) {
                updateVisibleNodes();
                lastDisplayedEntries = snapshot.entries();
            } else if (busy) {
                table.refresh();
            }
        }

        if (busy) {
            String current = snapshot.currentPath() == null ? "" : " · " + abbreviate(snapshot.currentPath().toString(), 72);
            progressLabel.setText(text("Scanne ", "Scanning ") + NUMBER.format(snapshot.entries())
                    + text(" Einträge · ", " entries · ")
                    + ByteFormat.rate(snapshot.entries(), snapshot.elapsedMillis()) + current);
            progressBar.setProgress(expectedVolumeBytes > 0
                    ? Math.min(0.97, (double) snapshot.physicalBytes() / expectedVolumeBytes)
                    : ProgressBar.INDETERMINATE_PROGRESS);
        }
        statisticsLabel.setText(NUMBER.format(snapshot.files()) + text(" Dateien · ", " files · ")
                + NUMBER.format(snapshot.directories()) + text(" Ordner · logisch ", " directories · logical ")
                + ByteFormat.bytes(snapshot.logicalBytes()) + text(" · physisch ", " · physical ")
                + ByteFormat.bytes(snapshot.physicalBytes()) + " · "
                + NUMBER.format(snapshot.offlineFiles()) + " offline · "
                + NUMBER.format(snapshot.errors()) + text(" nicht lesbar", " unreadable"));
    }

    private void focusFromChart(DiskScanner.ScanNode node) {
        focusedNode = node;
        updateVisibleNodes();
    }

    private void focus(DiskScanner.ScanNode node) {
        focusedNode = node;
        sunburst.setFocus(node);
        updateVisibleNodes();
    }

    private void updateVisibleNodes() {
        if (focusedNode == null) {
            visibleNodes.clear();
            breadcrumb.setText(text("Noch kein Scan", "No scan yet"));
            return;
        }
        List<DiskScanner.ScanNode> selection = new ArrayList<>(table.getSelectionModel().getSelectedItems());
        visibleNodes.setAll(focusedNode.children());
        selection.forEach(node -> table.getSelectionModel().select(node));
        breadcrumb.setText(focusedNode.displayPath());
        parentButton.setDisable(focusedNode.parent() == null);
        updateActionButtons();
    }

    private void updateActionButtons() {
        boolean hasSelection = !table.getSelectionModel().isEmpty();
        revealButton.setDisable(!hasSelection || table.getSelectionModel().getSelectedItems().size() != 1);
        deleteButton.setDisable(!hasSelection || busy || deleting);
    }

    private void updateBusyState() {
        scanButton.setDisable(busy || deleting);
        cancelButton.setDisable(!busy);
        chooseButton.setDisable(busy || deleting);
        pathField.setDisable(busy || deleting);
        volumeBox.setDisable(busy || deleting);
        parallelSpinner.setDisable(busy || deleting);
        progressBar.setVisible(busy);
        progressBar.setManaged(busy);
        deleteProgress.setVisible(deleting);
        table.setDisable(deleting);
        updateActionButtons();
    }

    private void reveal(DiskScanner.ScanNode node) {
        if (node == null) {
            return;
        }
        Path path = node.path();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                    try {
                        desktop.browseFileDirectory(path.toFile());
                        return;
                    } catch (IllegalArgumentException | UnsupportedOperationException | SecurityException ignored) {
                        // Fall back to the platform command below.
                    }
                }
            }
            if (os.contains("mac")) {
                new ProcessBuilder("open", "-R", path.toString()).start();
            } else if (os.contains("win")) {
                new ProcessBuilder("explorer.exe", "/select," + path).start();
            } else {
                new ProcessBuilder("xdg-open", node.directory() ? path.toString() : path.getParent().toString()).start();
            }
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException | SecurityException exception) {
            showError(text("Dateimanager konnte nicht geöffnet werden", "Could not open file manager"),
                    exception.getMessage());
        }
    }

    private void confirmDelete(Collection<DiskScanner.ScanNode> requestedNodes) {
        List<DiskScanner.ScanNode> nodes = requestedNodes.stream().filter(node -> node != null).distinct().toList();
        if (nodes.isEmpty() || busy || deleting) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                nodes.size() == 1
                        ? text("„", "\"") + nodes.getFirst().name()
                                + text("“ dauerhaft löschen?", "\" permanently?")
                        : NUMBER.format(nodes.size()) + text(" ausgewählte Einträge dauerhaft löschen?",
                                " selected items permanently?"),
                ButtonType.CANCEL, ButtonType.OK);
        alert.initOwner(stage);
        alert.setTitle(text("Löschen bestätigen", "Confirm deletion"));
        alert.setHeaderText(text("Dieser Vorgang kann nicht rückgängig gemacht werden.",
                "This operation cannot be undone."));
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        deleting = true;
        updateBusyState();
        progressLabel.setText(text("Lösche ", "Deleting ") + NUMBER.format(nodes.size())
                + text(" Einträge …", " items …"));
        Thread.ofVirtual().name("file-delete").start(() -> {
            ArrayList<DiskScanner.ScanNode> deleted = new ArrayList<>();
            ArrayList<String> failures = new ArrayList<>();
            for (DiskScanner.ScanNode node : nodes) {
                try {
                    deleteRecursively(node.path());
                    deleted.add(node);
                } catch (IOException | SecurityException exception) {
                    failures.add(node.name() + ": " + exception.getMessage());
                }
            }
            Platform.runLater(() -> finishDelete(nodes, deleted, failures));
        });
    }

    private void finishDelete(List<DiskScanner.ScanNode> requested, List<DiskScanner.ScanNode> deleted,
                              List<String> failures) {
        DiskScanner activeScanner = scanner;
        if (activeScanner != null) {
            activeScanner.recordDeleted(deleted);
        }
        requested.stream().map(DiskScanner.ScanNode::parent).distinct().forEach(parent -> {
            if (parent != null) {
                parent.removeChildren(deleted.stream().filter(node -> node.parent() == parent).toList());
            }
        });
        deleting = false;
        lastDisplayedEntries = -1;
        refresh(true);
        progressLabel.setText(NUMBER.format(deleted.size()) + text(" Einträge gelöscht", " items deleted"));
        updateBusyState();
        if (!failures.isEmpty()) {
            showError(text("Nicht alles konnte gelöscht werden", "Not everything could be deleted"),
                    String.join("\n", failures.subList(0, Math.min(8, failures.size()))));
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(path);
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void restoreSettings() {
        pathField.setText(preferences.get(PREF_PATH, ""));
        int savedParallelism = Math.max(1, Math.min(64,
                preferences.getInt(PREF_PARALLELISM, parallelSpinner.getValue())));
        parallelSpinner.getValueFactory().setValue(savedParallelism);
        try {
            sizeBasis = DiskScanner.SizeBasis.valueOf(
                    preferences.get(PREF_SIZE_BASIS, DiskScanner.SizeBasis.PHYSICAL.name()));
        } catch (IllegalArgumentException ignored) {
            sizeBasis = DiskScanner.SizeBasis.PHYSICAL;
        }
        logicalButton.setSelected(sizeBasis == DiskScanner.SizeBasis.LOGICAL);
        physicalButton.setSelected(sizeBasis == DiskScanner.SizeBasis.PHYSICAL);
        sizeColumn.setText(sizeBasis == DiskScanner.SizeBasis.LOGICAL
                ? text("Logisch", "Logical") : text("Physisch", "Physical"));
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR,
                message == null ? text("Unbekannter Fehler", "Unknown error") : message,
                ButtonType.OK);
        alert.initOwner(stage);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.showAndWait();
    }

    private static String status(DiskScanner.ScanNode node) {
        if (node.symbolicLink()) {
            return text("Link", "Link");
        }
        if (node.excludedVolume()) {
            return text("Anderes Volume", "Other volume");
        }
        if (node.offlineSelf()) {
            return text("Offline", "Offline");
        }
        if (node.directory() && node.offlineFiles() > 0) {
            return NUMBER.format(node.offlineFiles()) + " " + text("Offline", "offline");
        }
        return node.directory() ? text("Ordner", "Directory") : text("Lokal", "Local");
    }

    private static ColumnConstraints growColumn() {
        ColumnConstraints column = new ColumnConstraints();
        column.setHgrow(Priority.ALWAYS);
        return column;
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private static String formatDuration(long millis) {
        long seconds = millis / 1_000;
        return seconds < 60 ? String.format(numberLocale(), "%.1f s", millis / 1_000.0)
                : NUMBER.format(seconds / 60) + " min " + NUMBER.format(seconds % 60) + " s";
    }

    private static String abbreviate(String text, int maximum) {
        return text.length() <= maximum ? text : "…" + text.substring(text.length() - maximum + 1);
    }
}
