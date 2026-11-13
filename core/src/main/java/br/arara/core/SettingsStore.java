package br.arara.core;

public interface SettingsStore {
    Settings load();

    void save(Settings settings);
}
