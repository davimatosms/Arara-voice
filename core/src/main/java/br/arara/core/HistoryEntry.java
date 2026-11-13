package br.arara.core;

import java.time.LocalDateTime;

/** Uma linha de historico.jsonl: {"data":"2026-11-13T14:32:05","texto":"..."} */
public record HistoryEntry(LocalDateTime data, String texto) {}
