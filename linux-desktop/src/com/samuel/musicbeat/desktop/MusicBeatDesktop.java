package com.samuel.musicbeat.desktop;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

/** Offline-first MusicBeatSP desktop player for Linux. */
public final class MusicBeatDesktop {
    private static final Color BACKGROUND = new Color(16, 17, 20);
    private static final Color SURFACE = new Color(26, 27, 31);
    private static final Color SURFACE_HOVER = new Color(37, 39, 44);
    private static final Color TEXT = new Color(246, 246, 248);
    private static final Color MUTED = new Color(157, 159, 166);
    private static final Color ACCENT = new Color(161, 126, 255);
    private static final String[] EXTENSIONS = {
            ".mp3", ".flac", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav",
            ".aiff", ".aif", ".wma", ".ape", ".alac", ".wv"
    };

    private enum View { SONGS, QUEUE, PLAYLIST }

    private final JFrame frame = new JFrame("MusicBeatSP");
    private final Preferences preferences = Preferences.userNodeForPackage(MusicBeatDesktop.class);
    private final PlaylistStore playlistStore = new PlaylistStore();
    private final LibraryTableModel tableModel = new LibraryTableModel();
    private final JTable table = new JTable(tableModel);
    private final JTextField search = new JTextField();
    private final JLabel status = new JLabel("Choose a music folder to get started");
    private final JLabel nowPlayingTitle = new JLabel("Nothing playing");
    private final JLabel nowPlayingArtist = new JLabel("MusicBeatSP · offline player");
    private final JLabel lyricLine = new JLabel(" ");
    private final JButton playButton = button("▶");
    private final JButton removeButton = button("Remove selected");
    private final JButton moveUpButton = button("Move up");
    private final JButton moveDownButton = button("Move down");
    private final JButton deletePlaylistButton = button("Delete playlist");
    private final JComboBox<PlaylistStore.Playlist> playlistPicker = new JComboBox<>();
    private boolean playing;
    private final MpvIpcPlayer player = new MpvIpcPlayer(
            this::showPlayerError,
            state -> SwingUtilities.invokeLater(() -> updatePlaybackState(state)),
            () -> SwingUtilities.invokeLater(() -> { if (playing) skip(1); }));
    private List<Track> library = List.of();
    private final ArrayList<Track> queue = new ArrayList<>();
    private List<PlaylistStore.Playlist> playlists = List.of();
    private List<Track> activePlaybackList = List.of();
    private View activePlaybackView = View.SONGS;
    private String activePlaylistId;
    private List<LrcLyrics.Line> currentLyrics = List.of();
    private Track currentTrack;
    private Path musicFolder;
    private View view = View.SONGS;
    private int currentIndex = -1;
    private double currentPositionSeconds;
    private boolean updatingPlaylistPicker;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            configureLookAndFeel();
            new MusicBeatDesktop().show();
        });
    }

    private static void configureLookAndFeel() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) { /* Retain the JDK default if the desktop theme is unavailable. */ }
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("OptionPane.background", SURFACE);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("Button.background", SURFACE_HOVER);
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("TextField.background", SURFACE);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.caretForeground", TEXT);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("ComboBox.background", SURFACE);
        UIManager.put("ComboBox.foreground", TEXT);
    }

    private void show() {
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(900, 620));
        frame.setSize(1180, 780);
        frame.setLocationRelativeTo(null);
        frame.setBackground(BACKGROUND);
        frame.setLayout(new BorderLayout());
        frame.add(buildSidebar(), BorderLayout.WEST);
        frame.add(buildMain(), BorderLayout.CENTER);
        frame.add(buildPlayerBar(), BorderLayout.SOUTH);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { player.close(); }
        });
        try { refreshPlaylists(null); }
        catch (IOException error) { status.setText("Could not load saved playlists"); }
        queue.addAll(playlistStore.loadQueue());
        frame.setVisible(true);

        String saved = preferences.get("musicFolder", "");
        if (!saved.isBlank()) {
            try {
                Path folder = Path.of(saved);
                if (Files.isDirectory(folder)) loadFolder(folder);
            } catch (RuntimeException ignored) { /* A removed or malformed folder is simply forgotten. */ }
        }
        refreshTable();
    }

    private Component buildSidebar() {
        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(SURFACE);
        side.setPreferredSize(new Dimension(225, 0));
        side.setBorder(BorderFactory.createEmptyBorder(24, 18, 20, 18));

        JLabel brand = new JLabel("♫  MusicBeatSP");
        brand.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        brand.setForeground(TEXT);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(brand, BorderLayout.NORTH);

        JPanel nav = new JPanel(new GridBagLayout());
        nav.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(28, 0, 0, 0);
        c.anchor = GridBagConstraints.NORTHWEST;
        nav.add(navLabel("YOUR LIBRARY", true), c);
        c.insets = new Insets(12, 0, 0, 0);
        c.gridy = 1;
        nav.add(navButton("♫   Songs", View.SONGS), c);
        c.gridy = 2;
        nav.add(navButton("▤   Play queue", View.QUEUE), c);
        c.gridy = 3;
        nav.add(navButton("▧   Playlists", View.PLAYLIST), c);

        JButton choose = button("Add music folder");
        choose.setHorizontalAlignment(SwingConstants.LEFT);
        choose.addActionListener(e -> chooseFolder());
        JButton importPlaylist = button("Import M3U / M3U8");
        importPlaylist.setHorizontalAlignment(SwingConstants.LEFT);
        importPlaylist.addActionListener(e -> importM3u());
        JPanel bottom = new JPanel(new GridBagLayout());
        bottom.setOpaque(false);
        GridBagConstraints bottomC = new GridBagConstraints();
        bottomC.gridx = 0;
        bottomC.fill = GridBagConstraints.HORIZONTAL;
        bottomC.weightx = 1;
        bottomC.insets = new Insets(0, 0, 8, 0);
        bottom.add(choose, bottomC);
        bottomC.gridy = 1;
        bottom.add(importPlaylist, bottomC);

        side.add(top, BorderLayout.NORTH);
        side.add(nav, BorderLayout.CENTER);
        side.add(bottom, BorderLayout.SOUTH);
        return side;
    }

    private JButton navButton(String label, View target) {
        JButton nav = button(label);
        nav.setHorizontalAlignment(SwingConstants.LEFT);
        nav.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        nav.setContentAreaFilled(false);
        nav.addActionListener(event -> setView(target));
        return nav;
    }

    private Component buildMain() {
        JPanel main = new JPanel(new BorderLayout(0, 16));
        main.setBackground(BACKGROUND);
        main.setBorder(BorderFactory.createEmptyBorder(26, 30, 18, 30));

        JPanel header = new JPanel(new BorderLayout(12, 14));
        header.setOpaque(false);
        JPanel top = new JPanel(new BorderLayout(12, 8));
        top.setOpaque(false);
        JPanel titlePanel = new JPanel(new BorderLayout(0, 5));
        titlePanel.setOpaque(false);
        titlePanel.add(navLabel("LOCAL MUSIC · NO ACCOUNT REQUIRED", true), BorderLayout.NORTH);
        JLabel title = new JLabel("Songs");
        title.setName("view-title");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 31));
        title.setForeground(TEXT);
        titlePanel.add(title, BorderLayout.CENTER);
        search.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        search.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(55, 57, 63)),
                BorderFactory.createEmptyBorder(9, 12, 9, 12)));
        search.setToolTipText("Search title, artist, album, or file path");
        search.setPreferredSize(new Dimension(280, 40));
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refreshTable(); }
            @Override public void removeUpdate(DocumentEvent e) { refreshTable(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshTable(); }
        });
        top.add(titlePanel, BorderLayout.WEST);
        top.add(search, BorderLayout.EAST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        JButton playSelected = button("Play selected");
        playSelected.addActionListener(e -> playSelected());
        JButton addQueue = button("Add to queue");
        addQueue.addActionListener(e -> addSelectedToQueue());
        JButton addPlaylist = button("Add to playlist");
        addPlaylist.addActionListener(e -> addSelectedToPlaylist());
        JButton createPlaylist = button("New playlist");
        createPlaylist.addActionListener(e -> createPlaylist(selectedTracks()));
        removeButton.addActionListener(e -> removeSelectedFromCollection());
        moveUpButton.addActionListener(e -> moveSelected(-1));
        moveDownButton.addActionListener(e -> moveSelected(1));
        deletePlaylistButton.addActionListener(e -> deleteCurrentPlaylist());

        playlistPicker.setPreferredSize(new Dimension(205, 36));
        playlistPicker.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list,
                        value instanceof PlaylistStore.Playlist playlist ? playlist.name() : "Choose playlist",
                        index, selected, focus);
                return this;
            }
        });
        playlistPicker.addActionListener(e -> {
            if (!updatingPlaylistPicker) refreshTable();
        });

        actions.add(playSelected);
        actions.add(addQueue);
        actions.add(addPlaylist);
        actions.add(createPlaylist);
        actions.add(removeButton);
        actions.add(moveUpButton);
        actions.add(moveDownButton);
        actions.add(playlistPicker);
        actions.add(deletePlaylistButton);
        header.add(top, BorderLayout.NORTH);
        header.add(actions, BorderLayout.CENTER);

        configureTable();
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(39, 40, 45)));
        scroll.getViewport().setBackground(BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(18);

        status.setForeground(MUTED);
        status.setBorder(BorderFactory.createEmptyBorder(0, 1, 0, 0));
        main.add(header, BorderLayout.NORTH);
        main.add(scroll, BorderLayout.CENTER);
        main.add(status, BorderLayout.SOUTH);
        return main;
    }

    private Component buildPlayerBar() {
        JPanel bar = new JPanel(new BorderLayout(18, 0));
        bar.setBackground(SURFACE);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(48, 49, 54)),
                BorderFactory.createEmptyBorder(10, 22, 10, 22)));
        JPanel info = new JPanel(new BorderLayout(0, 3));
        info.setOpaque(false);
        info.setPreferredSize(new Dimension(300, 60));
        nowPlayingTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        nowPlayingTitle.setForeground(TEXT);
        nowPlayingArtist.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        nowPlayingArtist.setForeground(MUTED);
        lyricLine.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        lyricLine.setForeground(ACCENT);
        info.add(nowPlayingTitle, BorderLayout.NORTH);
        info.add(nowPlayingArtist, BorderLayout.CENTER);
        info.add(lyricLine, BorderLayout.SOUTH);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 9, 2));
        controls.setOpaque(false);
        JButton previous = button("◀◀");
        JButton next = button("▶▶");
        previous.addActionListener(e -> skip(-1));
        next.addActionListener(e -> skip(1));
        playButton.setBackground(ACCENT);
        playButton.setForeground(Color.WHITE);
        playButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
        playButton.addActionListener(e -> togglePlayback());
        controls.add(previous);
        controls.add(playButton);
        controls.add(next);

        JLabel version = new JLabel("Linux · 2.1.0");
        version.setForeground(MUTED);
        version.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        bar.add(info, BorderLayout.WEST);
        bar.add(controls, BorderLayout.CENTER);
        bar.add(version, BorderLayout.EAST);
        return bar;
    }

    private void configureTable() {
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowHeight(50);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setBackground(BACKGROUND);
        table.setForeground(TEXT);
        table.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        table.setSelectionBackground(new Color(59, 50, 79));
        table.setSelectionForeground(Color.WHITE);
        table.getTableHeader().setBackground(BACKGROUND);
        table.getTableHeader().setForeground(MUTED);
        table.getTableHeader().setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        table.getTableHeader().setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(48, 49, 54)));
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setMaxWidth(48);
        table.getColumnModel().getColumn(0).setMinWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(310);
        table.getColumnModel().getColumn(2).setPreferredWidth(220);
        table.getColumnModel().getColumn(3).setPreferredWidth(220);
        table.getColumnModel().getColumn(4).setPreferredWidth(80);
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                    boolean focused, int row, int column) {
                Component cell = super.getTableCellRendererComponent(t, value, selected, focused, row, column);
                if (!selected) {
                    cell.setBackground(BACKGROUND);
                    cell.setForeground(column == 0 ? MUTED : TEXT);
                }
                if (cell instanceof JLabel label) label.setBorder(BorderFactory.createEmptyBorder(0, column == 0 ? 12 : 8, 0, 8));
                return cell;
            }
        };
        table.setDefaultRenderer(Object.class, renderer);
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) playSelected();
            }
        });
        table.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("SPACE"), "play-selected");
        table.getActionMap().put("play-selected", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { playSelected(); }
        });
    }

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose a music folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (musicFolder != null) chooser.setCurrentDirectory(musicFolder.toFile());
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) loadFolder(chooser.getSelectedFile().toPath());
    }

    private void loadFolder(Path folder) {
        musicFolder = folder.toAbsolutePath().normalize();
        preferences.put("musicFolder", musicFolder.toString());
        status.setText("Scanning " + musicFolder + "…");
        new SwingWorker<List<Track>, Void>() {
            @Override protected List<Track> doInBackground() throws Exception {
                List<Path> paths = new ArrayList<>();
                try (Stream<Path> files = Files.walk(folder)) {
                    files.filter(Files::isRegularFile).filter(MusicBeatDesktop::isAudioFile).forEach(paths::add);
                }
                List<Track> tracks = new ArrayList<>(paths.size());
                for (Path path : paths) {
                    if (isCancelled()) break;
                    tracks.add(Track.read(path));
                }
                tracks.sort(Comparator.comparing(Track::title, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(track -> track.path().toString(), String.CASE_INSENSITIVE_ORDER));
                return tracks;
            }
            @Override protected void done() {
                try {
                    library = get();
                    refreshActivePlaybackList();
                    refreshTable();
                    status.setText(library.size() + (library.size() == 1 ? " song" : " songs") + " in " + musicFolder);
                } catch (Exception error) {
                    status.setText("Could not read that folder");
                    JOptionPane.showMessageDialog(frame, error.getMessage(), "MusicBeatSP", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void setView(View next) {
        view = next;
        Component title = findNamed(frame, "view-title");
        if (title instanceof JLabel label) {
            label.setText(switch (view) {
                case SONGS -> "Songs";
                case QUEUE -> "Play queue";
                case PLAYLIST -> "Playlists";
            });
        }
        refreshTable();
    }

    private static Component findNamed(Component root, String name) {
        if (name.equals(root.getName())) return root;
        if (root instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                Component found = findNamed(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void refreshTable() {
        List<Track> source = switch (view) {
            case SONGS -> library;
            case QUEUE -> queue;
            case PLAYLIST -> selectedPlaylist() == null ? List.of() : selectedPlaylist().tracks();
        };
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        List<Track> filtered = source.stream().filter(track -> query.isEmpty()
                || track.title().toLowerCase(Locale.ROOT).contains(query)
                || track.artist().toLowerCase(Locale.ROOT).contains(query)
                || track.album().toLowerCase(Locale.ROOT).contains(query)
                || track.path().toString().toLowerCase(Locale.ROOT).contains(query)).toList();
        tableModel.setTracks(filtered);
        configureActionVisibility();
        if (view == View.QUEUE) status.setText(queue.size() + " tracks queued · order is saved on this device");
        else if (view == View.PLAYLIST) {
            PlaylistStore.Playlist playlist = selectedPlaylist();
            status.setText(playlist == null ? "Create a playlist or import an M3U/M3U8 file" :
                    playlist.tracks().size() + " tracks · " + playlist.name());
        } else status.setText(library.size() + (library.size() == 1 ? " song" : " songs") +
                (musicFolder == null ? " · Choose a local music folder" : " in " + musicFolder));
    }

    private void configureActionVisibility() {
        boolean collection = view == View.QUEUE || view == View.PLAYLIST;
        removeButton.setVisible(collection);
        moveUpButton.setVisible(collection);
        moveDownButton.setVisible(collection);
        playlistPicker.setVisible(view == View.PLAYLIST);
        deletePlaylistButton.setVisible(view == View.PLAYLIST && selectedPlaylist() != null);
        frame.revalidate();
    }

    private List<Track> selectedTracks() {
        int[] rows = table.getSelectedRows();
        List<Track> selected = new ArrayList<>(rows.length);
        for (int row : rows) selected.add(tableModel.trackAt(table.convertRowIndexToModel(row)));
        return selected;
    }

    private List<Track> sourceForCurrentView() {
        return switch (view) {
            case SONGS -> library;
            case QUEUE -> queue;
            case PLAYLIST -> selectedPlaylist() == null ? List.of() : selectedPlaylist().tracks();
        };
    }

    private void playSelected() {
        List<Track> selected = selectedTracks();
        if (selected.isEmpty()) return;
        List<Track> sequence = sourceForCurrentView();
        activePlaybackView = view;
        activePlaylistId = view == View.PLAYLIST && selectedPlaylist() != null ? selectedPlaylist().id() : null;
        playTrack(selected.get(0), sequence);
    }

    private void playTrack(Track track, List<Track> sequence) {
        if (sequence.isEmpty()) sequence = List.of(track);
        activePlaybackList = sequence;
        currentIndex = sequence.indexOf(track);
        if (currentIndex < 0) {
            ArrayList<Track> copy = new ArrayList<>(sequence);
            copy.add(track);
            activePlaybackList = copy;
            currentIndex = copy.size() - 1;
        }
        currentTrack = track;
        currentPositionSeconds = 0;
        currentLyrics = LrcLyrics.read(track.path());
        updateLyrics();
        nowPlayingTitle.setText(track.title());
        nowPlayingArtist.setText(track.artist() + " · " + track.album());
        player.play(track.path());
        playing = true;
        playButton.setText("Ⅱ");
    }

    private void togglePlayback() {
        if (currentTrack == null) {
            playSelected();
            return;
        }
        player.togglePause();
        playing = !playing;
        playButton.setText(playing ? "Ⅱ" : "▶");
    }

    private void updatePlaybackState(MpvIpcPlayer.State state) {
        currentPositionSeconds = state.seconds();
        playing = !state.paused();
        playButton.setText(playing ? "Ⅱ" : "▶");
        updateLyrics();
    }

    private void updateLyrics() {
        String line = LrcLyrics.at(currentLyrics, currentPositionSeconds);
        lyricLine.setText(line.isBlank() ? " " : line);
        lyricLine.setToolTipText(line.isBlank() ? "No adjacent .lrc lyrics file" : line);
    }

    private void skip(int direction) {
        List<Track> sequence = activePlaybackList;
        if (sequence.isEmpty()) sequence = library;
        if (sequence.isEmpty()) return;
        int next = currentTrack == null ? (direction > 0 ? 0 : sequence.size() - 1)
                : Math.floorMod(currentIndex + direction, sequence.size());
        playTrack(sequence.get(next), sequence);
    }

    private void addSelectedToQueue() {
        List<Track> tracks = selectedTracks();
        if (tracks.isEmpty()) return;
        Set<Path> queued = new HashSet<>();
        queue.forEach(track -> queued.add(track.path()));
        int added = 0;
        for (Track track : tracks) if (queued.add(track.path())) { queue.add(track); added++; }
        persistQueue();
        if (view == View.QUEUE) refreshTable();
        status.setText(added + " added to the play queue");
    }

    private void persistQueue() {
        try { playlistStore.saveQueue(queue); }
        catch (IOException error) { status.setText("Could not save the play queue: " + error.getMessage()); }
    }

    private void createPlaylist(List<Track> tracks) {
        String name = JOptionPane.showInputDialog(frame, "Playlist name", "New playlist");
        if (name == null) return;
        try {
            PlaylistStore.Playlist created = playlistStore.saveNew(name, tracks);
            refreshPlaylists(created.id());
            setView(View.PLAYLIST);
            status.setText("Created “" + created.name() + "” with " + tracks.size() + " tracks");
        } catch (IOException error) {
            showPlaylistError(error);
        }
    }

    private void addSelectedToPlaylist() {
        List<Track> tracks = selectedTracks();
        if (tracks.isEmpty()) return;
        if (playlists.isEmpty()) {
            createPlaylist(tracks);
            return;
        }
        @SuppressWarnings("unchecked") JComboBox<PlaylistStore.Playlist> picker = new JComboBox<>(playlists.toArray(PlaylistStore.Playlist[]::new));
        picker.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list,
                        value instanceof PlaylistStore.Playlist playlist ? playlist.name() : "",
                        index, selected, focus);
                return this;
            }
        });
        if (JOptionPane.showConfirmDialog(frame, picker, "Add to playlist", JOptionPane.OK_CANCEL_OPTION)
                != JOptionPane.OK_OPTION) return;
        PlaylistStore.Playlist target = (PlaylistStore.Playlist) picker.getSelectedItem();
        if (target == null) return;
        ArrayList<Track> combined = new ArrayList<>(target.tracks());
        Set<Path> existing = new HashSet<>();
        combined.forEach(track -> existing.add(track.path()));
        for (Track track : tracks) if (existing.add(track.path())) combined.add(track);
        try {
            playlistStore.replace(target, combined);
            refreshPlaylists(target.id());
            if (view == View.PLAYLIST) refreshTable();
            status.setText("Added " + (combined.size() - target.tracks().size()) + " tracks to “" + target.name() + "”");
        } catch (IOException error) { showPlaylistError(error); }
    }

    private void importM3u() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import an offline M3U or M3U8 playlist");
        chooser.setFileFilter(new FileNameExtensionFilter("M3U playlists (*.m3u, *.m3u8)", "m3u", "m3u8"));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        Path source = chooser.getSelectedFile().toPath();
        status.setText("Reading playlist…");
        new SwingWorker<PlaylistStore.Imported, Void>() {
            @Override protected PlaylistStore.Imported doInBackground() throws Exception { return playlistStore.importFile(source); }
            @Override protected void done() {
                try {
                    PlaylistStore.Imported imported = get();
                    if (imported.tracks().isEmpty()) {
                        JOptionPane.showMessageDialog(frame,
                                "No accessible local audio tracks were found. " + imported.skipped() + " entries were skipped.",
                                "Playlist not imported", JOptionPane.INFORMATION_MESSAGE);
                        refreshTable();
                        return;
                    }
                    String name = (String) JOptionPane.showInputDialog(frame, "Playlist name", "Import M3U playlist",
                            JOptionPane.PLAIN_MESSAGE, null, null, imported.suggestedName());
                    if (name == null) { refreshTable(); return; }
                    PlaylistStore.Playlist created = playlistStore.saveNew(name, imported.tracks());
                    refreshPlaylists(created.id());
                    setView(View.PLAYLIST);
                    String result = "Imported “" + created.name() + "”: " + created.tracks().size() + " added, " +
                            imported.skipped() + " skipped.";
                    status.setText(result);
                    JOptionPane.showMessageDialog(frame, result, "M3U import complete", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    JOptionPane.showMessageDialog(frame, cause.getMessage(), "Could not import playlist", JOptionPane.ERROR_MESSAGE);
                    refreshTable();
                }
            }
        }.execute();
    }

    private void refreshPlaylists(String selectId) throws IOException {
        playlists = playlistStore.loadPlaylists();
        updatingPlaylistPicker = true;
        DefaultComboBoxModel<PlaylistStore.Playlist> model = new DefaultComboBoxModel<>();
        playlists.forEach(model::addElement);
        playlistPicker.setModel(model);
        if (selectId != null) {
            for (int i = 0; i < model.getSize(); i++) {
                if (model.getElementAt(i).id().equals(selectId)) { playlistPicker.setSelectedIndex(i); break; }
            }
        }
        updatingPlaylistPicker = false;
        refreshActivePlaybackList();
        refreshTable();
    }

    private PlaylistStore.Playlist selectedPlaylist() {
        return (PlaylistStore.Playlist) playlistPicker.getSelectedItem();
    }

    private void removeSelectedFromCollection() {
        List<Track> selected = selectedTracks();
        if (selected.isEmpty()) return;
        Set<Path> paths = new HashSet<>();
        selected.forEach(track -> paths.add(track.path()));
        if (view == View.QUEUE) {
            queue.removeIf(track -> paths.contains(track.path()));
            persistQueue();
        } else if (view == View.PLAYLIST) {
            PlaylistStore.Playlist current = selectedPlaylist();
            if (current == null) return;
            try {
                List<Track> remaining = current.tracks().stream().filter(track -> !paths.contains(track.path())).toList();
                playlistStore.replace(current, remaining);
                refreshPlaylists(current.id());
            } catch (IOException error) { showPlaylistError(error); }
        }
        refreshActivePlaybackList();
        refreshTable();
    }

    private void moveSelected(int direction) {
        if (table.getSelectedRowCount() != 1) return;
        Track track = selectedTracks().get(0);
        List<Track> base = sourceForCurrentView();
        int index = base.indexOf(track);
        int target = index + direction;
        if (index < 0 || target < 0 || target >= base.size()) return;
        ArrayList<Track> reordered = new ArrayList<>(base);
        Track swapped = reordered.set(target, reordered.get(index));
        reordered.set(index, swapped);
        if (view == View.QUEUE) {
            queue.clear();
            queue.addAll(reordered);
            persistQueue();
        } else if (view == View.PLAYLIST) {
            PlaylistStore.Playlist selected = selectedPlaylist();
            if (selected == null) return;
            try {
                playlistStore.replace(selected, reordered);
                refreshPlaylists(selected.id());
            } catch (IOException error) { showPlaylistError(error); return; }
        }
        refreshActivePlaybackList();
        refreshTable();
        int visibleRow = tableModel.indexOf(track);
        if (visibleRow >= 0) table.setRowSelectionInterval(visibleRow, visibleRow);
    }

    private void refreshActivePlaybackList() {
        if (activePlaybackView == View.QUEUE) {
            activePlaybackList = queue;
        } else if (activePlaybackView == View.PLAYLIST) {
            PlaylistStore.Playlist activePlaylist = playlists.stream()
                    .filter(playlist -> playlist.id().equals(activePlaylistId)).findFirst().orElse(null);
            if (activePlaylist == null) {
                activePlaybackView = View.SONGS;
                activePlaylistId = null;
                activePlaybackList = library;
            } else activePlaybackList = activePlaylist.tracks();
        } else activePlaybackList = library;
        currentIndex = currentTrack == null ? -1 : activePlaybackList.indexOf(currentTrack);
    }

    private void deleteCurrentPlaylist() {
        PlaylistStore.Playlist selected = selectedPlaylist();
        if (selected == null) return;
        if (JOptionPane.showConfirmDialog(frame, "Delete playlist “" + selected.name() + "”?",
                "Delete playlist", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try {
            playlistStore.delete(selected);
            refreshPlaylists(null);
            refreshActivePlaybackList();
        } catch (IOException error) { showPlaylistError(error); }
    }

    private void showPlayerError(String message) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, message,
                "Playback unavailable", JOptionPane.ERROR_MESSAGE));
    }

    private void showPlaylistError(IOException error) {
        JOptionPane.showMessageDialog(frame, error.getMessage(), "Playlist error", JOptionPane.ERROR_MESSAGE);
    }

    private static boolean isAudioFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) if (name.endsWith(extension)) return true;
        return false;
    }

    private static JLabel navLabel(String text, boolean heading) {
        JLabel label = new JLabel(text);
        label.setForeground(heading ? MUTED : TEXT);
        label.setFont(new Font(Font.SANS_SERIF, heading ? Font.BOLD : Font.PLAIN, heading ? 11 : 14));
        return label;
    }

    private static JButton button(String text) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(56, 57, 63)),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));
        button.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        return button;
    }

    private static final class LibraryTableModel extends AbstractTableModel {
        private final String[] columns = {"#", "TITLE", "ARTIST", "ALBUM", "TIME"};
        private List<Track> tracks = List.of();
        void setTracks(List<Track> value) { tracks = value; fireTableDataChanged(); }
        Track trackAt(int row) { return tracks.get(row); }
        int indexOf(Track track) { return tracks.indexOf(track); }
        @Override public int getRowCount() { return tracks.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int row, int column) {
            Track track = tracks.get(row);
            return switch (column) {
                case 0 -> row + 1;
                case 1 -> track.title();
                case 2 -> track.artist();
                case 3 -> track.album();
                case 4 -> track.duration();
                default -> "";
            };
        }
        @Override public boolean isCellEditable(int row, int column) { return false; }
    }
}
