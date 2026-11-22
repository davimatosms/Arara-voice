package br.arara.app;

import br.arara.core.AraraCore;
import br.arara.core.HistoryStore;
import br.arara.core.Settings;
import br.arara.core.SettingsStore;
import br.arara.core.UpdateChecker;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import br.arara.core.Sounds;
import javax.swing.Timer;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Configurações: Geral, Atualizações, Atalho, Voz e captura, Histórico e privacidade. */
final class SettingsPage extends MainView.Page {
    private static final String DEFAULT_MIC = "Microfone padrão do sistema";
    private static final String RECORDING = "Aperte as teclas…";

    private final SettingsStore settings;
    private final HistoryStore history;
    private final Runnable onChange;
    private final JPanel content = Ui.column();
    private final JPanel keys = Ui.row(0);
    private final JLabel version = Ui.label("Versão", Theme.regular(13), Theme.TEXT_SOFT);
    final JButton search = Ui.link("Procurar agora", Theme.bold(13), Theme.ACTIVE);
    private final Timer searchBack = new Timer(4000, e -> searchState("Procurar agora", Theme.ACTIVE, false));
    /** Cartão "Atualizações" (o ScreenshotTest desenha só ele nos estados do "Procurar agora"). */
    JPanel updatesCard;
    private UpdateUi updates;
    private UpdateChecker.Update found;

    SettingsPage(SettingsStore settings, HistoryStore history, Runnable onChange) {
        this.settings = settings;
        this.history = history;
        this.onChange = onChange;
        add(Ui.scroll(content));
        build();
    }

    @Override
    String title() {
        return "Configurações";
    }

    @Override
    String subtitle() {
        return "Tudo roda no seu computador. Nada sai daqui.";
    }

    /** Monta uma vez só: refazer a tela a cada mudança tiraria o foco do teclado do controle. */
    private void build() {
        Settings s = settings.load();
        keys.add(Ui.keycaps(s.hotkey()));

        content.add(appearance(s));

        content.add(section("Geral", "Como o Arara se comporta no dia a dia.",
                row(Ui.STARTUP, Ui.MAC ? "Fica na barra de menus, pronto para ouvir." : "Fica na bandeja do sistema, pronto para ouvir.",
                        Ui.toggle(s.startWithWindows(), Ui.STARTUP, on -> update(x -> x.withStartWithWindows(on)))),
                row("Mostrar a arara ao ditar", "Pop-up pequeno que não rouba o foco da janela.",
                        Ui.toggle(s.showPopup(), "Mostrar a arara ao ditar", on -> update(x -> x.withShowPopup(on)))),
                row("Estilo do pop-up", "Só a arara: sem fundo, no canto da tela, voando enquanto transcreve.",
                        Ui.segmented(new String[] {"Completo", "Só a arara"}, s.popupStyle().ordinal(), "Estilo do pop-up",
                                i -> update(x -> x.withPopupStyle(Settings.PopupStyle.values()[i]))))));

        search.addActionListener(e -> search());
        searchBack.setRepeats(false);
        updatesCard = section("Atualizações", Ui.row(6, version, search),
                row("Verificar atualizações", "Avisa quando sair uma versão nova. Nunca instala sozinho.",
                        Ui.toggle(s.checkUpdates(), "Verificar atualizações", on -> update(x -> x.withCheckUpdates(on)))));
        content.add(updatesCard);

        JButton record = Ui.button("Gravar", null, Ui.Kind.OUTLINE);
        record.getAccessibleContext().setAccessibleName("Gravar novo atalho");
        record.addActionListener(e -> recordHotkey(record));
        content.add(section("Atalho", "Aperte para começar e de novo para terminar, ou segure para falar e solte para terminar.",
                row("Atalho para ditar", Ui.MAC
                        ? "Teclas ou um botão do mouse. Evite Option sem Cmd ou Ctrl (digita símbolos)."
                        : "Teclas ou um botão do mouse. Evite só Ctrl+Alt (vira AltGr no ABNT2).",
                        Ui.row(8, keys, record)),
                row("Modo do atalho", "Segurar não funciona no Wayland.",
                        Ui.segmented(new String[] {"Apertar para ligar e desligar", "Segurar para falar"},
                                s.holdToTalk() ? 1 : 0, "Modo do atalho", i -> update(x -> x.withHoldToTalk(i == 1)))),
                row("Esc cancela", "Para de ouvir e não cola nada.",
                        Ui.toggle(s.escCancels(), "Esc cancela", on -> update(x -> x.withEscCancels(on))))));

        JComboBox<String> mic = micChoice(s, m -> update(x -> x.withMicrophone(m)));
        mic.setPreferredSize(new Dimension(280, 40));
        Settings.Model[] models = Settings.Model.values();
        Settings.Accel[] accels = Settings.Accel.values();
        JComponent micRow = row("Microfone", "16 kHz, mono.", mic);
        JComponent modelRow = row("Modelo", "Mais preciso usa mais memória e demora um pouco mais.",
                Ui.segmented(new String[] {"Leve", "Equilibrado", "Preciso"}, s.model().ordinal(), "Modelo",
                        i -> update(x -> x.withModel(models[i]))));
        JComponent accelRow = Ui.MAC
                ? row("Aceleração", "A GPU (Metal) acelera o ditado. Se travar, use só o processador.",
                        Ui.segmented(new String[] {"Automática (GPU quando der)", "Só processador"},
                                br.arara.core.AraraCore.gpuOn(s) ? 0 : 1, "Aceleração",
                                i -> update(x -> x.withAccel(i == 0 ? Settings.Accel.AUTO : Settings.Accel.CPU_ONLY))))
                : row("Aceleração", "Vulkan usa a placa de vídeo (NVIDIA, AMD ou Intel).",
                        Ui.segmented(new String[] {"CPU", "Vulkan"}, Math.min(1, s.accel().ordinal()), "Aceleração",
                                i -> update(x -> x.withAccel(accels[i]))));
        content.add(section("Voz e captura", Ui.MAC ? "Microfone, modelo e aceleração." : "Microfone, modelo e velocidade.",
                micRow, modelRow, accelRow));

        content.add(soundSection(s));

        if (AraraCore.LINUX) content.add(linuxCard(Onboarding.Linux.detect()));

        JButton open = Ui.button("Abrir pasta", "folder", Ui.Kind.OUTLINE);
        open.addActionListener(e -> openDataDir());
        JButton clear = Ui.button("Apagar tudo", "trash-2", Ui.Kind.OUTLINE);
        clear.addActionListener(e -> {
            if (HistoryPage.confirmClear(this, history)) onChange.run();
        });
        content.add(section("Histórico e privacidade", "Seus ditados ficam só aqui, sem criptografia.",
                row("Pasta do histórico",
                        Ui.MAC ? "~/Library/Application Support/Arara/historico.jsonl" : "%APPDATA%\\Arara\\historico.jsonl", open),
                row("Apagar histórico", "Remove todos os ditados salvos. Não dá para desfazer.", clear)));

        JButton uninstall = UninstallDialog.button();
        uninstall.addActionListener(e -> UninstallDialog.open(this));
        JPanel ask = new JPanel(new BorderLayout(16, 0));
        ask.setOpaque(false);
        ask.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, Theme.BORDER), new EmptyBorder(14, 0, 14, 0)));
        ask.add(Ui.label("Quer ir embora? Sem problema, a gente deixa tudo arrumado.", Theme.semibold(15), Theme.TEXT));
        ask.add(Ui.centerY(uninstall), BorderLayout.EAST);
        content.add(section("Desinstalar", "Remove o Arara deste computador. Seus ditados só saem se você pedir.", ask));
    }

    /** Card só do Linux: sessão, atalho e colagem, com os atalhos para refazer a configuração. */
    static JPanel linuxCard(Onboarding.Linux l) {
        boolean wayland = l.wayland();
        String cmd = Onboarding.Linux.command();
        String session = !wayland ? "X11" : l.gnome() ? "Wayland (GNOME)" : l.kde() ? "Wayland (KDE Plasma)" : "Wayland";
        JButton copy = Ui.button("Copiar comando", null, Ui.Kind.OUTLINE);
        Timer back = new Timer(2000, e -> copy.setText("Copiar comando"));
        back.setRepeats(false);
        copy.addActionListener(e -> {
            Onboarding.copy(cmd);
            copy.setText("Copiado");
            back.restart();
        });
        JButton redo = Ui.button("Refazer permissão", null, Ui.Kind.OUTLINE);
        redo.addActionListener(e -> {
            AraraCore.resetPastePermission();
            redo.setText("Pedido na próxima colagem");
            redo.setEnabled(false);
        });
        JLabel none = Ui.label("", Theme.regular(12), Theme.TEXT_SOFT);
        JComponent atalho = row("Atalho", wayland ? "Do sistema: " + cmd : "Global do Arara (X11)", wayland ? copy : none);
        JComponent colagem = row("Colagem", !wayland ? "Ctrl+V simulado"
                : l.gnome() ? "Cola automática: portal do GNOME" : "wtype ou ydotool; sem eles, cole com Ctrl+V",
                wayland && l.gnome() ? redo : none);
        return section("Linux", wayland ? "Wayland: o sistema cuida do atalho e da permissão de colar." : "X11: atalho e colagem funcionam direto.",
                row("Sessão", session, none), atalho, colagem);
    }

    /** Aparência: as 5 araras e o modo; embaixo, a chave do ícone (desligada por padrão). Tudo aplica na hora. */
    private JPanel appearance(Settings s) {
        JPanel macaws = Ui.column(Ui.caption("ARARA"), Ui.gap(8), Appearance.tiles(settings, 102, 160, 2, false, 10));
        JPanel mode = Ui.column(Ui.caption("MODO"), Ui.gap(8), Appearance.modes(settings, true, 152), Ui.gap(10),
                Ui.label("Padrão: Escuro", Theme.regular(12), Theme.TEXT_SOFT));
        JPanel pick = new JPanel(new BorderLayout(16, 0));
        pick.setOpaque(false);
        pick.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, Theme.BORDER), new EmptyBorder(14, 0, 18, 0)));
        pick.add(macaws, BorderLayout.WEST);
        pick.add(mode, BorderLayout.EAST);
        JComponent preview = Appearance.iconPreview(settings);
        JLabel title = Ui.label("Aparência", Theme.bold(18), Theme.TEXT);
        title.setIcon(Ui.icon("palette", 22, Theme.TEXT));
        title.setIconTextGap(10);
        return section(title, Ui.label("Escolha a sua arara e o modo. O app muda na hora, sem reiniciar.", Theme.regular(13),
                        Theme.TEXT_SOFT), pick,
                row("Usar o ícone da arara escolhida", "<html>Só com o app aberto: " + (Ui.MAC ? "Dock e barra de menus."
                                : "bandeja, janela e barra de tarefas (no Mac, Dock e barra de menus).")
                                + "<br>O ícone do programa, do atalho e do instalador é sempre a canindé.</html>",
                        Ui.row(24, preview, Ui.toggle(s.macawIcon(), "Usar o ícone da arara escolhida", on -> {
                            update(x -> x.withMacawIcon(on));
                            preview.repaint();
                        }))));
    }

    /** Liga o cartão "Atualizações" ao fluxo de atualização (versão atual e "Procurar agora"). */
    void updates(UpdateUi ui) {
        updates = ui;
        String v = ui.current();
        version.setText((v != null ? "Versão " + v : "Versão de desenvolvimento") + "  ·");
    }

    /** Procurar agora → Procurando… → "Você tem a versão mais nova" (some em 4 s), "Nova versão X · Ver" ou "Sem internet". */
    private void search() {
        if (updates == null || searchBack.isRunning() && found == null) return;
        if (found != null) {
            updates.open(found);
            return;
        }
        if (search.getText().startsWith("Procurando")) return;
        searchState("Procurando…", Theme.TEXT_SOFT, false);
        updates.search(u -> {
            found = u.orElse(null);
            if (found != null) {
                searchState("Nova versão " + found.version() + " · Ver", Theme.SUPPORT, false);
            } else {
                searchState("Você tem a versão mais nova", Theme.READY, true);
                searchBack.restart();
            }
        }, e -> searchState("Sem internet · Tentar de novo", Theme.ERROR, false));
    }

    void searchState(String text, Color color, boolean check) {
        search.setText(text);
        search.setForeground(color);
        search.setIcon(check ? Ui.icon("check", 13, color) : null);
        search.getAccessibleContext().setAccessibleName(text);
    }

    /** Lista de microfones; onChange recebe o nome escolhido, ou null para o padrão do sistema. */
    static JComboBox<String> micChoice(Settings s, Consumer<String> onChange) {
        List<String> mics = new ArrayList<>(List.of(DEFAULT_MIC));
        mics.addAll(AraraCore.microphones());
        if (s.microphone() != null && !mics.contains(s.microphone())) mics.add(s.microphone());
        JComboBox<String> mic = new JComboBox<>(mics.toArray(String[]::new));
        mic.setSelectedItem(s.microphone() == null ? DEFAULT_MIC : s.microphone());
        mic.getAccessibleContext().setAccessibleName("Microfone");
        mic.addActionListener(e -> onChange.accept(DEFAULT_MIC.equals(mic.getSelectedItem()) ? null : (String) mic.getSelectedItem()));
        return mic;
    }

    private JPanel soundSection(Settings s) {
        Settings.Sound[] kinds = Settings.Sound.values();
        Settings.SoundLength[] lengths = Settings.SoundLength.values();
        JSlider volume = new JSlider(0, 100, s.soundVolume());
        volume.setPreferredSize(new Dimension(220, 32));
        volume.setOpaque(false);
        volume.getAccessibleContext().setAccessibleName("Volume do som");
        // grava ao soltar: arrastar não precisa regravar o config.json a cada pixel
        volume.addChangeListener(e -> {
            if (!volume.getValueIsAdjusting()) update(x -> x.withSoundVolume(volume.getValue()));
        });
        JButton listen = Ui.button("Ouvir", null, Ui.Kind.OUTLINE);
        listen.getAccessibleContext().setAccessibleName("Ouvir o som");
        listen.addActionListener(e -> {
            Settings now = settings.load().withSoundOn(true).withSoundVolume(volume.getValue());
            Sounds.play(now, true);
            Timer t = new Timer(Sounds.millis(now) + 150, ev -> Sounds.play(now, false));
            t.setRepeats(false);
            t.start();
        });
        return section("Som", "Um toque curto ao começar e ao parar de ouvir.",
                row("Tocar som", "Avisa que o Arara começou e parou de ouvir.",
                        Ui.toggle(s.soundOn(), "Tocar som", on -> update(x -> x.withSoundOn(on)))),
                row("Tipo", "Arara é um piado; Pixel, bipes de jogo; Suave, duas notas.",
                        Ui.segmented(new String[] {"Arara", "Pixel", "Suave"}, s.sound().ordinal(), "Tipo de som",
                                i -> update(x -> x.withSound(kinds[i])))),
                row("Volume", "Só do som do Arara, não do computador.", volume),
                row("Duração", "Mais curto ou mais longo que o normal.",
                        Ui.segmented(new String[] {"Curta", "Normal", "Longa"}, s.soundLength().ordinal(), "Duração do som",
                                i -> update(x -> x.withSoundLength(lengths[i])))),
                row("Testar", "Toca o início e o fim com estes valores.", listen));
    }

    private void update(UnaryOperator<Settings> change) {
        settings.save(change.apply(settings.load()));
        onChange.run();
    }

    private void recordHotkey(JButton button) {
        recordHotkey(button, hotkey -> {
            keys.removeAll();
            keys.add(Ui.keycaps(hotkey));
            keys.revalidate();
            update(x -> x.withHotkey(hotkey));
        });
    }

    /** Grava o próximo atalho apertado com o botão em foco; recusa os ruins com um aviso. Também usado na primeira execução. */
    static void recordHotkey(JButton button, Consumer<String> onHotkey) {
        String idle = button.getText();
        if (idle.equals(RECORDING)) return; // já está gravando
        button.setText(RECORDING);
        KeyAdapter listener = new KeyAdapter() {
            boolean other; // outra tecla entrou desde que o modificador foi apertado

            private void accept(String hotkey) {
                try {
                    br.arara.core.Hotkey.parse(hotkey);
                } catch (IllegalArgumentException ex) {
                    JOptionPane.showMessageDialog(button, "Não consegui usar essa tecla como atalho. Que tal tentar outra?", "Atalho",
                            JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                String tip = tip(hotkey);
                if (tip != null && JOptionPane.showOptionDialog(button, tip, "Atalho", JOptionPane.DEFAULT_OPTION,
                        JOptionPane.INFORMATION_MESSAGE, null, new String[] {"Usar mesmo assim", "Escolher outra"}, "Escolher outra") != 0) {
                    return;
                }
                button.removeKeyListener(this);
                button.setText(idle);
                onHotkey.accept(hotkey);
            }

            @Override
            public void keyPressed(KeyEvent e) {
                e.consume();
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    button.transferFocus();
                    return;
                }
                if (e.getKeyCode() == KeyEvent.VK_ALT_GRAPH) other = false; // o AltGr manda um Ctrl falso antes
                else other = true;
                String hotkey = hotkey(e.getModifiersEx(), e.getKeyCode());
                if (hotkey == null) return; // só modificador até agora
                accept(hotkey);
            }

            /** Ctrl/Alt/Shift direito sozinho só vale ao soltar, para não atrapalhar quem quer Ctrl direito + tecla. */
            @Override
            public void keyReleased(KeyEvent e) {
                e.consume();
                String solo = other ? null : soloModifier(e.getKeyCode(), e.getKeyLocation());
                if ((e.getModifiersEx() & (KeyEvent.CTRL_DOWN_MASK | KeyEvent.ALT_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK
                        | KeyEvent.META_DOWN_MASK | KeyEvent.ALT_GRAPH_DOWN_MASK)) == 0) other = false;
                if (solo != null) accept(solo);
            }
        };
        button.addKeyListener(listener);
        button.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                button.removeKeyListener(listener);
                button.removeFocusListener(this);
                button.setText(idle);
            }
        });
        button.requestFocusInWindow();
    }

    /** "RCtrl", "RAlt" ou "RShift" para o modificador direito (AltGr conta como Alt direito); null nos demais. */
    static String soloModifier(int keyCode, int location) {
        boolean right = location == KeyEvent.KEY_LOCATION_RIGHT;
        return switch (keyCode) {
            case KeyEvent.VK_CONTROL -> right ? "RCtrl" : "Ctrl";
            case KeyEvent.VK_SHIFT -> right ? "RShift" : "Shift";
            case KeyEvent.VK_ALT -> right ? "RAlt" : "Alt";
            case KeyEvent.VK_ALT_GRAPH -> "RAlt";
            case KeyEvent.VK_META, KeyEvent.VK_WINDOWS -> "Win";
            default -> null;
        };
    }

    /** "Ctrl+Shift+Space" a partir da tecla apertada; null se só um modificador foi apertado. */
    static String hotkey(int modifiersEx, int keyCode) {
        return hotkey(modifiersEx, keyCode, Ui.MAC);
    }

    /** No macOS, Cmd (META) entra no atalho e Alt se chama Option; no Windows, a tecla Win fica de fora. */
    static String hotkey(int modifiersEx, int keyCode, boolean mac) {
        switch (keyCode) {
            case KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_ALT, KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_META,
                 KeyEvent.VK_WINDOWS -> { return null; }
            default -> { }
        }
        StringBuilder sb = new StringBuilder();
        if (mac && (modifiersEx & KeyEvent.META_DOWN_MASK) != 0) sb.append("Cmd+");
        if ((modifiersEx & KeyEvent.CTRL_DOWN_MASK) != 0) sb.append("Ctrl+");
        if ((modifiersEx & KeyEvent.ALT_DOWN_MASK) != 0) sb.append(mac ? "Option+" : "Alt+");
        if ((modifiersEx & KeyEvent.SHIFT_DOWN_MASK) != 0) sb.append("Shift+");
        if (keyCode == KeyEvent.VK_SPACE) sb.append("Space");
        else if ((keyCode >= KeyEvent.VK_A && keyCode <= KeyEvent.VK_Z) || (keyCode >= KeyEvent.VK_0 && keyCode <= KeyEvent.VK_9))
            sb.append((char) keyCode);
        else if (keyCode >= KeyEvent.VK_F1 && keyCode <= KeyEvent.VK_F12) sb.append('F').append(keyCode - KeyEvent.VK_F1 + 1);
        else if (keyCode >= KeyEvent.VK_F13 && keyCode <= KeyEvent.VK_F24) sb.append('F').append(keyCode - KeyEvent.VK_F13 + 13);
        else if (keyCode == KeyEvent.VK_PAGE_UP) sb.append("PageUp");
        else if (keyCode == KeyEvent.VK_PAGE_DOWN) sb.append("PageDown");
        else if (keyCode == KeyEvent.VK_SCROLL_LOCK) sb.append("ScrollLock");
        else if (keyCode == KeyEvent.VK_CAPS_LOCK) sb.append("CapsLock");
        else sb.append(KeyEvent.getKeyText(keyCode).replace(" ", ""));
        return sb.toString();
    }

    /** Teclas sem função própria: só o aviso geral de tecla única. */
    private static final java.util.regex.Pattern SOLO_SAFE = java.util.regex.Pattern.compile(
            "F([1-9]|1[0-9]|2[0-4])|Pause|ScrollLock|RCtrl|RAlt|RShift");

    /** Aviso de tecla única (amarelo), ou null se o atalho é uma combinação. */
    static String soloWarning(String hotkey) {
        return hotkey.contains("+") ? null
                : "Uma tecla só é mais rápida, mas pode disparar sem querer. A combinação Ctrl+Shift+Espaço é a mais segura.";
    }

    /** Dica gentil (nunca bloqueia) para o que mantém a função normal; null se é seguro. */
    static String tip(String hotkey) {
        return tip(hotkey, Ui.MAC);
    }

    static String tip(String hotkey, boolean mac) {
        if (!hotkey.contains("+")) {
            if (hotkey.startsWith("Mouse") || SOLO_SAFE.matcher(hotkey).matches()) return null;
            String name = hotkey.equals("Space") ? "Espaço" : hotkey;
            String what = hotkey.equals("Delete") ? "apagando" : hotkey.equals("CapsLock") ? "ligando as maiúsculas" : "funcionando normalmente";
            return "Dica: a tecla " + name + " continua " + what + " enquanto for o atalho, e isso pode atrapalhar no dia a dia."
                    + " Se preferir, F8 ou Ctrl direito funcionam bem sozinhos.";
        }
        if (mac) {
            boolean cmd = hotkey.contains("Cmd+"), ctrl = hotkey.contains("Ctrl+"), opt = hotkey.contains("Option+");
            boolean shift = hotkey.contains("Shift+"), fKey = hotkey.matches(".*\\bF\\d+$");
            switch (hotkey) {
                case "Cmd+Space", "Cmd+Option+Space" -> { return "Dica: esse atalho também abre o Spotlight ou o Finder."; }
                case "Ctrl+Space", "Ctrl+Option+Space" -> { return "Dica: esse atalho também troca a fonte de entrada do teclado."; }
                case "Cmd+Shift+Space" -> { return "Dica: esse atalho também é da Inteligência Visual do macOS."; }
                case "Cmd+Ctrl+Space" -> { return "Dica: esse atalho também abre os emojis e símbolos."; }
                default -> { }
            }
            if (opt && !cmd && !ctrl && !fKey) return "Dica: Option sem Cmd ou Ctrl pode digitar símbolos no Mac.";
            if (!cmd && !ctrl && !opt && !shift && !fKey) return "Dica: sem modificador, a tecla também digita.";
            return null;
        }
        boolean ctrl = hotkey.contains("Ctrl+"), alt = hotkey.contains("Alt+"), shift = hotkey.contains("Shift+");
        if (ctrl && alt && !shift) return "Dica: Ctrl+Alt vira AltGr no teclado ABNT2. Juntar o Shift evita isso.";
        return null;
    }

    private void openDataDir() {
        try {
            Path dir = Files.createDirectories(AraraCore.defaultDataDir());
            Desktop.getDesktop().open(dir.toFile());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Não consegui abrir a pasta: " + AraraCore.defaultDataDir(), "Pasta do histórico",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private static JPanel section(String title, String subtitle, JComponent... rows) {
        return section(title, Ui.label(subtitle, Theme.regular(13), Theme.TEXT_SOFT), rows);
    }

    private static JPanel section(String title, JComponent subtitle, JComponent... rows) {
        return section(Ui.label(title, Theme.bold(18), Theme.TEXT), subtitle, rows);
    }

    private static JPanel section(JLabel title, JComponent subtitle, JComponent... rows) {
        Ui.Round card = Ui.card(new BorderLayout());
        card.setBorder(new EmptyBorder(20, 24, 8, 24));
        JPanel col = Ui.column(title, Ui.gap(2), subtitle, Ui.gap(12));
        for (JComponent r : rows) {
            r.setAlignmentX(0f);
            col.add(r);
        }
        card.add(col);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setBorder(new EmptyBorder(0, 0, 20, 0));
        wrap.add(card);
        wrap.setAlignmentX(0f);
        return wrap;
    }

    private static JPanel row(String title, String description, JComponent control) {
        JPanel r = new JPanel(new BorderLayout(16, 0));
        r.setOpaque(false);
        r.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, Theme.BORDER), new EmptyBorder(14, 0, 14, 0)));
        r.add(Ui.column(Ui.label(title, Theme.semibold(15), Theme.TEXT), Ui.gap(4),
                Ui.label(description, Theme.regular(12), Theme.TEXT_SOFT)));
        r.add(Ui.centerY(control), BorderLayout.EAST);
        return r;
    }
}
