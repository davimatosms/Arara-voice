package br.arara.app;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * macOS: tira o Arara do Dock (e do Cmd+Tab) enquanto a janela está fechada e devolve quando ela abre, deixando só o
 * ícone da barra de menus. Troca a "activation policy" do NSApplication: {@code Regular} aparece no Dock,
 * {@code Accessory} não. O Java não expõe isso, então chama o Objective-C por JNA. Se qualquer passo falhar, o Dock
 * simplesmente continua como está.
 */
final class MacDock {
    private static final Logger LOG = Logger.getLogger("br.arara");
    private static final long POLICY_REGULAR = 0;
    private static final long POLICY_ACCESSORY = 1;

    /** Só o que precisamos do runtime do Objective-C. Funções fixas (não variádicas): no arm64 o objc_msgSend exige isso. */
    interface ObjC extends Library {
        Pointer objc_getClass(String name);

        Pointer sel_registerName(String name);

        Pointer objc_msgSend(Pointer receiver, Pointer selector);

        long objc_msgSend(Pointer receiver, Pointer selector, long arg);
    }

    /** {@code void (*)(void *)} do libdispatch. */
    interface DispatchFunction extends Callback {
        void invoke(Pointer context);
    }

    interface Dispatch extends Library {
        void dispatch_async_f(Pointer queue, Pointer context, DispatchFunction work);
    }

    /** Os callbacks precisam ficar vivos até o libdispatch chamá-los; senão o JNA os coleta e a chamada derruba a JVM. */
    private static final Set<DispatchFunction> PENDING = ConcurrentHashMap.newKeySet();
    private static final Object LOCK = new Object();
    private static ObjC objc;
    private static Dispatch dispatch;
    private static Pointer mainQueue;
    private static boolean broken;
    /** Último estado pedido; evita repetir a troca (a janela avisa "aberta" por mais de um caminho). */
    private static Boolean shown;

    private MacDock() {}

    /** Quando o Dock deve esconder: só no Mac, com a opção ligada e com a barra de menus para abrir a janela de volta. */
    static boolean shouldHide(boolean mac, boolean hideDock, boolean hasTray) {
        return mac && hideDock && hasTray;
    }

    /** Aparece no Dock e vem para a frente (a janela vai abrir). */
    static void show() {
        apply(true);
    }

    /** Sai do Dock; o ícone da barra de menus continua. */
    static void hide() {
        apply(false);
    }

    private static void apply(boolean visible) {
        synchronized (LOCK) {
            if (broken || Boolean.valueOf(visible).equals(shown)) return;
            try {
                init();
                shown = visible;
                onMainThread(() -> setPolicy(visible));
            } catch (Throwable e) { // UnsatisfiedLinkError, falta de permissão para extrair a lib do JNA, etc.
                broken = true;
                LOG.log(Level.WARNING, "Dock: não consegui trocar a política do app; o ícone fica como está", e);
            }
        }
    }

    private static void init() {
        if (objc != null) return;
        objc = Native.load("objc", ObjC.class);
        dispatch = Native.load("System", Dispatch.class);
        // dispatch_get_main_queue() é só o endereço desta variável global
        mainQueue = NativeLibrary.getInstance("System").getGlobalVariableAddress("_dispatch_main_q");
    }

    /** O AppKit quer a troca na thread principal dele, que não é a do Swing. */
    private static void onMainThread(Runnable work) {
        DispatchFunction[] self = new DispatchFunction[1];
        self[0] = context -> {
            try {
                work.run();
            } catch (Throwable e) {
                LOG.log(Level.WARNING, "Dock: falha ao trocar a política do app", e);
            } finally {
                PENDING.remove(self[0]);
            }
        };
        PENDING.add(self[0]);
        dispatch.dispatch_async_f(mainQueue, null, self[0]);
    }

    private static void setPolicy(boolean visible) {
        Pointer app = objc.objc_msgSend(objc.objc_getClass("NSApplication"), objc.sel_registerName("sharedApplication"));
        objc.objc_msgSend(app, objc.sel_registerName("setActivationPolicy:"), visible ? POLICY_REGULAR : POLICY_ACCESSORY);
        // Sem isto, ao voltar de Accessory a janela abre atrás das outras e a barra de menus não aparece.
        if (visible) objc.objc_msgSend(app, objc.sel_registerName("activateIgnoringOtherApps:"), 1L);
    }
}
