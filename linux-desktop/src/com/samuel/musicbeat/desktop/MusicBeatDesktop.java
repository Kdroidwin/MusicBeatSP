package com.samuel.musicbeat.desktop;

import javax.swing.BorderFactory;
import javax.swing.DefaultListSelectionModel;
import javax.swing.JButton;
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
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
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
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

/** First native Linux increment: local library browsing and playback. */
public final class MusicBeatDesktop {
    private static final Color BACKGROUND = new Color(16, 17, 20);
    private static final Color SURFACE = new Color(26, 27, 31);
    private static final Color SURFACE_HOVER = new Color(37, 39, 44);
    private static final Color TEXT = new Color(246, 246, 248);
    private static final Color MUTED = new Color(157, 159, 166);
    private static final Color ACCENT = new Color(161, 126, 255);
    private static final String[] EXTENSIONS = {
            ".mp3", ".flac", ".m4a", ".aac", ".ogg", ".opus", ".wav",
            ".aiff", ".aif", ".wma", ".ape", ".alac", ".wv"
    };

    private final JFrame frame = new JFrame("MusicBeat");
    private final Preferences preferences = Preferences.userNodeForPackage(MusicBeatDesktop.class);
    private final LibraryTableModel tableModel = new LibraryTableModel();
    private final JTable table = new JTable(tableModel);
    private final JTextField search = new JTextField();
    private final JLabel status = new JLabel("Choose a music folder to get started");
    private final JLabel nowPlayingTitle = new JLabel("Nothing playing");
    private final JLabel nowPlayingArtist = new JLabel("MusicBeat for Linux");
    private final JButton playButton = button("▶");
    private final MpvIpcPlayer player = new MpvIpcPlayer(this::showPlayerError);
    private List<Track> library = List.of();
    private Path musicFolder;
    private int currentIndex = -1;
    private boolean playing;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            configureLookAndFeel();
            new MusicBeatDesktop().show();
        });
    }

    private static void configureLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Keep the JDK's built-in look and feel if the platform one fails.
        }
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("OptionPane.background", SURFACE);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("Button.background", SURFACE_HOVER);
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("TextField.background", SURFACE);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.caretForeground", TEXT);
        UIManager.put("Label.foreground", TEXT);
    }

    private void show() {
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(850, 590));
        frame.setSize(1120, 760);
        frame.setLocationRelativeTo(null);
        frame.setBackground(BACKGROUND);
        frame.setLayout(new BorderLayout());
        frame.add(buildSidebar(), BorderLayout.WEST);
        frame.add(buildMain(), BorderLayout.CENTER);
        frame.add(buildPlayerBar(), BorderLayout.SOUTH);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { player.close(); }
        });
        frame.setVisible(true);

        String saved = preferences.get("musicFolder", "");
        if (!saved.isBlank() && Files.isDirectory(Path.of(saved))) {
            loadFolder(Path.of(saved));
        }
    }

    private Component buildSidebar() {
        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(SURFACE);
        side.setPreferredSize(new Dimension(220, 0));
        side.setBorder(BorderFactory.createEmptyBorder(24, 18, 20, 18));

        JLabel brand = new JLabel("♫  MusicBeat");
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
        c.insets = new Insets(14, 10, 0, 0);
        c.gridy = 1;
        nav.add(navLabel("♫   Songs", false), c);
        c.gridy = 2;
        nav.add(navLabel("▤   Folders", false), c);

        JButton choose = button("Add music folder");
        choose.setHorizontalAlignment(SwingConstants.LEFT);
        choose.addActionListener(e -> chooseFolder());
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(choose, BorderLayout.NORTH);

        side.add(top, BorderLayout.NORTH);
        side.add(nav, BorderLayout.CENTER);
        side.add(bottom, BorderLayout.SOUTH);
        return side;
    }

    private Component buildMain() {
        JPanel main = new JPanel(new BorderLayout(0, 18));
        main.setBackground(BACKGROUND);
        main.setBorder(BorderFactory.createEmptyBorder(28, 32, 20, 32));

        JPanel header = new JPanel(new BorderLayout(12, 10));
        header.setOpaque(false);
        JPanel titlePanel = new JPanel(new BorderLayout(0, 6));
        titlePanel.setOpaque(false);
        JLabel eyebrow = navLabel("LOCAL MUSIC", true);
        JLabel title = new JLabel("Songs");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 32));
        title.setForeground(TEXT);
        titlePanel.add(eyebrow, BorderLayout.NORTH);
        titlePanel.add(title, BorderLayout.CENTER);

        search.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        search.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(55, 57, 63)),
                BorderFactory.createEmptyBorder(9, 12, 9, 12)));
        search.setToolTipText("Search title, artist, or album");
        search.setPreferredSize(new Dimension(260, 40));
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { filterTable(); }
            @Override public void removeUpdate(DocumentEvent e) { filterTable(); }
            @Override public void changedUpdate(DocumentEvent e) { filterTable(); }
        });
        header.add(titlePanel, BorderLayout.WEST);
        header.add(search, BorderLayout.EAST);

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
                BorderFactory.createEmptyBorder(12, 22, 12, 22)));
        JPanel info = new JPanel(new GridBagLayout());
        info.setOpaque(false);
        info.setPreferredSize(new Dimension(280, 52));
        info.setLayout(new BorderLayout(0, 3));
        nowPlayingTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        nowPlayingTitle.setForeground(TEXT);
        nowPlayingArtist.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        nowPlayingArtist.setForeground(MUTED);
        info.add(nowPlayingTitle, BorderLayout.CENTER);
        info.add(nowPlayingArtist, BorderLayout.SOUTH);

        JPanel controls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 9, 0));
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

        JLabel version = new JLabel("Linux preview");
        version.setForeground(MUTED);
        version.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        bar.add(info, BorderLayout.WEST);
        bar.add(controls, BorderLayout.CENTER);
        bar.add(version, BorderLayout.EAST);
        return bar;
    }

    private void configureTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
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
                if (cell instanceof JLabel label) {
                    label.setBorder(BorderFactory.createEmptyBorder(0, column == 0 ? 12 : 8, 0, 8));
                }
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
        int result = chooser.showOpenDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) loadFolder(chooser.getSelectedFile().toPath());
    }

    private void loadFolder(Path folder) {
        musicFolder = folder;
        preferences.put("musicFolder", folder.toAbsolutePath().toString());
        status.setText("Scanning " + folder + "…");
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
                    filterTable();
                    status.setText(library.size() + (library.size() == 1 ? " song" : " songs") + " in " + folder);
                } catch (Exception error) {
                    status.setText("Could not read that folder");
                    JOptionPane.showMessageDialog(frame, error.getMessage(), "MusicBeat", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void filterTable() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        List<Track> filtered = library.stream().filter(track -> query.isEmpty()
                || track.title().toLowerCase(Locale.ROOT).contains(query)
                || track.artist().toLowerCase(Locale.ROOT).contains(query)
                || track.album().toLowerCase(Locale.ROOT).contains(query)).toList();
        tableModel.setTracks(filtered);
    }

    private void playSelected() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        Track track = tableModel.trackAt(table.convertRowIndexToModel(row));
        play(track);
    }

    private void play(Track track) {
        currentIndex = library.indexOf(track);
        nowPlayingTitle.setText(track.title());
        nowPlayingArtist.setText(track.artist());
        player.play(track.path());
        playing = true;
        playButton.setText("Ⅱ");
    }

    private void togglePlayback() {
        if (currentIndex < 0) {
            playSelected();
            return;
        }
        player.togglePause();
        playing = !playing;
        playButton.setText(playing ? "Ⅱ" : "▶");
    }

    private void skip(int direction) {
        if (library.isEmpty()) return;
        int next = currentIndex < 0 ? 0 : Math.floorMod(currentIndex + direction, library.size());
        play(library.get(next));
    }

    private void showPlayerError(String message) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, message,
                "Playback unavailable", JOptionPane.ERROR_MESSAGE));
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
                BorderFactory.createEmptyBorder(8, 13, 8, 13)));
        button.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        return button;
    }

    private static final class LibraryTableModel extends AbstractTableModel {
        private final String[] columns = {"#", "TITLE", "ARTIST", "ALBUM", "TIME"};
        private List<Track> tracks = List.of();
        void setTracks(List<Track> value) { tracks = value; fireTableDataChanged(); }
        Track trackAt(int row) { return tracks.get(row); }
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
