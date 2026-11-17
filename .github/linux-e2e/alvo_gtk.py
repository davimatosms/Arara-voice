#!/usr/bin/env python3
"""Cliente Wayland nativo de teste (GTK3): campo de texto focado; depois de N segundos grava o conteúdo num arquivo.
Uso: GDK_BACKEND=wayland alvo_gtk.py <saida> <segundos>"""
import sys
import gi
gi.require_version('Gtk', '3.0')
from gi.repository import Gtk, GLib

out, secs = sys.argv[1], int(sys.argv[2])
win = Gtk.Window(title='AlvoGtk')
entry = Gtk.Entry()
win.add(entry)
win.set_default_size(400, 80)
win.connect('destroy', Gtk.main_quit)
win.show_all()
entry.grab_focus()


def fim():
    open(out, 'w').write(entry.get_text())
    Gtk.main_quit()
    return False


GLib.timeout_add_seconds(secs, fim)
Gtk.main()
