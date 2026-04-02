#!/usr/bin/env python3
"""Dump structure and event summaries from NTT DoCoMo MLD/MFi files.

This script is intentionally conservative: it only decodes fields that are
either visible in the sample corpus or corroborated by the shipped player.
Unknown commands are still preserved in the textual output.
"""

from __future__ import annotations

import argparse
import json
import struct
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


TIMEBASES = {
    0x0: 6,
    0x1: 12,
    0x2: 24,
    0x3: 48,
    0x4: 96,
    0x5: 192,
    0x6: 384,
    0x8: 15,
    0x9: 30,
    0xA: 60,
    0xB: 120,
    0xC: 240,
    0xD: 480,
    0xE: 960,
}


SYSTEM_EVENT_NAMES = {
    0xB0: "master_volume",
    0xC0: "tempo_tb_6",
    0xC1: "tempo_tb_12",
    0xC2: "tempo_tb_24",
    0xC3: "tempo_tb_48",
    0xC4: "tempo_tb_96",
    0xC5: "tempo_tb_192",
    0xC6: "tempo_tb_384",
    0xC8: "tempo_tb_15",
    0xC9: "tempo_tb_30",
    0xCA: "tempo_tb_60",
    0xCB: "tempo_tb_120",
    0xCC: "tempo_tb_240",
    0xCD: "tempo_tb_480",
    0xCE: "tempo_tb_960",
    0xD0: "cue_point",
    0xDC: "nop_type_2",
    0xDD: "loop_point",
    0xDE: "nop",
    0xDF: "end_of_track",
    0xE0: "program_change",
    0xE1: "bank_change",
    0xE2: "channel_volume",
    0xE3: "pan",
    0xE4: "pitch_bend",
    0xE5: "channel_assign",
    0xE6: "expression",
    0xE7: "pitch_bend_range",
    0xE8: "fine_pitch_or_pcm_volume",
    0xE9: "fine_pitch_or_pcm_pan",
    0xEA: "modulation_depth",
    0xFF: "machine_dependent",
}


@dataclass
class NoteEvent:
    tick: int
    delta: int
    status: int
    voice: int
    note: int
    gate: int
    velocity: int | None
    octave_shift: int | None


@dataclass
class SystemEvent:
    tick: int
    delta: int
    command: int
    value: int
    name: str
    part: int | None = None
    payload_length: int | None = None
    payload_hex: str | None = None
    timebase: int | None = None
    tempo: int | None = None


@dataclass
class TrackSummary:
    index: int
    length: int
    total_ticks: int
    note_events: int
    system_events: int
    machine_events: int
    machine_blocks: list[dict[str, Any]]
    loops: list[dict[str, Any]]
    cue_points: list[dict[str, Any]]
    tempos: list[dict[str, Any]]
    first_events: list[dict[str, Any]]


def be16(data: bytes, off: int) -> int:
    return struct.unpack_from(">H", data, off)[0]


def be32(data: bytes, off: int) -> int:
    return struct.unpack_from(">I", data, off)[0]


def decode_text(payload: bytes) -> str:
    for encoding in ("cp932", "shift_jis", "ascii"):
        try:
            return payload.decode(encoding)
        except Exception:
            continue
    return payload.hex()


def parse_info_chunks(data: bytes, start: int) -> tuple[list[dict[str, Any]], int, bool]:
    chunks: list[dict[str, Any]] = []
    note_has_extra = False
    off = start

    while off + 4 <= len(data):
        chunk_id = data[off : off + 4]
        if chunk_id == b"trac":
            break
        if off + 6 > len(data):
            raise ValueError(f"truncated info chunk at 0x{off:X}")

        size = be16(data, off + 4)
        payload = data[off + 6 : off + 6 + size]
        chunk_name = chunk_id.decode("ascii", errors="replace")
        entry: dict[str, Any] = {
            "offset": off,
            "id": chunk_name,
            "size": size,
            "payload_hex": payload.hex(),
        }

        if chunk_id in {b"titl", b"copy", b"supt", b"date", b"vers", b"auth", b"code", b"prot"}:
            entry["text"] = decode_text(payload)
        elif chunk_id == b"sorc" and payload:
            entry["value"] = payload[0]
        elif chunk_id == b"note" and size >= 2:
            note_has_extra = payload[-1] != 0
            entry["note_has_extra_byte"] = note_has_extra
        elif chunk_id == b"exst" and size >= 2:
            entry["exst_size"] = be16(payload, 0)

        chunks.append(entry)
        off += 6 + size

    return chunks, off, note_has_extra


def parse_track(track_index: int, payload: bytes, note_has_extra: bool) -> TrackSummary:
    off = 0
    tick = 0
    note_count = 0
    system_count = 0
    machine_count = 0
    first_events: list[dict[str, Any]] = []
    machine_blocks: list[dict[str, Any]] = []
    loops: list[dict[str, Any]] = []
    cue_points: list[dict[str, Any]] = []
    tempos: list[dict[str, Any]] = []

    while off < len(payload):
        if off + 2 > len(payload):
            raise ValueError(f"truncated event in track {track_index} at 0x{off:X}")

        delta = payload[off]
        status = payload[off + 1]
        off += 2
        tick += delta

        if status == 0xFF:
            if off + 1 > len(payload):
                raise ValueError(f"truncated FF event in track {track_index} at 0x{off:X}")
            command = payload[off]
            off += 1

            if command >= 0xF0:
                if off + 2 > len(payload):
                    raise ValueError(f"truncated machine event in track {track_index} at 0x{off:X}")
                length = be16(payload, off)
                off += 2
                body = payload[off : off + length]
                off += length
                machine_count += 1
                event = SystemEvent(
                    tick=tick,
                    delta=delta,
                    command=command,
                    value=0,
                    name=SYSTEM_EVENT_NAMES.get(command, "machine_dependent"),
                    payload_length=length,
                    payload_hex=body.hex(),
                )
                machine_blocks.append(
                    {
                        "tick": tick,
                        "command": f"0x{command:02X}",
                        "payload_length": length,
                        "payload_hex": body.hex(),
                    }
                )
            else:
                if off + 1 > len(payload):
                    raise ValueError(f"truncated system event in track {track_index} at 0x{off:X}")
                value = payload[off]
                off += 1
                system_count += 1

                part = None
                if 0xE0 <= command <= 0xEF:
                    part = (value >> 6) & 0x03

                event = SystemEvent(
                    tick=tick,
                    delta=delta,
                    command=command,
                    value=value,
                    name=SYSTEM_EVENT_NAMES.get(command, f"cmd_{command:02X}"),
                    part=part,
                )

                if 0xC0 <= command <= 0xCF:
                    event.timebase = TIMEBASES.get(command & 0x0F)
                    event.tempo = value
                    tempos.append(
                        {
                            "tick": tick,
                            "command": f"0x{command:02X}",
                            "timebase": event.timebase,
                            "tempo": value,
                        }
                    )
                elif command == 0xDD:
                    loops.append({"tick": tick, "value": value})
                elif command == 0xD0:
                    cue_points.append({"tick": tick, "value": value})

            if len(first_events) < 20:
                first_events.append(asdict(event))
            continue

        if off + 1 > len(payload):
            raise ValueError(f"truncated note in track {track_index} at 0x{off:X}")

        gate = payload[off]
        off += 1
        attr = payload[off] if note_has_extra else None
        if note_has_extra:
            off += 1

        note = NoteEvent(
            tick=tick,
            delta=delta,
            status=status,
            voice=(status >> 6) & 0x03,
            note=status & 0x3F,
            gate=gate,
            velocity=((attr >> 2) & 0x3F) if attr is not None else None,
            octave_shift=(attr & 0x03) if attr is not None else None,
        )
        note_count += 1
        if len(first_events) < 20:
            first_events.append(asdict(note))

    return TrackSummary(
        index=track_index,
        length=len(payload),
        total_ticks=tick,
        note_events=note_count,
        system_events=system_count,
        machine_events=machine_count,
        machine_blocks=machine_blocks,
        loops=loops,
        cue_points=cue_points,
        tempos=tempos,
        first_events=first_events,
    )


def parse_mld(path: Path) -> dict[str, Any]:
    data = path.read_bytes()
    if len(data) < 13:
        raise ValueError("file too small")

    magic = data[:4].decode("ascii", errors="replace")
    size_field = be32(data, 4)
    header_length = be16(data, 8)
    major = data[10]
    minor = data[11]
    track_count = data[12]

    info_chunks, off, note_has_extra = parse_info_chunks(data, 13)
    tracks: list[TrackSummary] = []

    while off < len(data):
        chunk_id = data[off : off + 4]
        if chunk_id != b"trac":
            raise ValueError(f"unexpected chunk {chunk_id!r} at 0x{off:X}")
        length = be32(data, off + 4)
        payload = data[off + 8 : off + 8 + length]
        tracks.append(parse_track(len(tracks), payload, note_has_extra))
        off += 8 + length

    return {
        "path": str(path),
        "file_size": len(data),
        "magic": magic,
        "size_field": size_field,
        "expected_size_field": len(data) - 8,
        "header_length": header_length,
        "major_type": major,
        "minor_type": minor,
        "track_count": track_count,
        "note_has_extra_byte": note_has_extra,
        "info_chunks": info_chunks,
        "tracks": [asdict(track) for track in tracks],
    }


def print_human(summary: dict[str, Any]) -> None:
    def emit(text: str) -> None:
        encoding = sys.stdout.encoding or "utf-8"
        sys.stdout.buffer.write((text + "\n").encode(encoding, "backslashreplace"))

    emit(f"File: {summary['path']}")
    emit(
        "Header: "
        f"magic={summary['magic']} "
        f"size_field=0x{summary['size_field']:X} "
        f"expected=0x{summary['expected_size_field']:X} "
        f"header_length=0x{summary['header_length']:X} "
        f"major=0x{summary['major_type']:02X} "
        f"minor=0x{summary['minor_type']:02X} "
        f"tracks={summary['track_count']} "
        f"note_extra={summary['note_has_extra_byte']}"
    )
    emit("")
    emit("Info chunks:")
    for chunk in summary["info_chunks"]:
        line = f"  0x{chunk['offset']:04X} {chunk['id']} size={chunk['size']}"
        if "text" in chunk:
            line += f" text={chunk['text']!r}"
        elif "value" in chunk:
            line += f" value=0x{chunk['value']:02X}"
        elif "note_has_extra_byte" in chunk:
            line += f" note_extra={chunk['note_has_extra_byte']}"
        elif "exst_size" in chunk:
            line += f" exst_size={chunk['exst_size']}"
        emit(line)
    emit("")
    for track in summary["tracks"]:
        emit(
            f"Track {track['index']}: "
            f"length={track['length']} "
            f"ticks={track['total_ticks']} "
            f"notes={track['note_events']} "
            f"system={track['system_events']} "
            f"machine={track['machine_events']}"
        )
        if track["tempos"]:
            emit("  tempos:")
            for tempo in track["tempos"]:
                emit(
                    "    "
                    f"tick={tempo['tick']} "
                    f"cmd={tempo['command']} "
                    f"timebase={tempo['timebase']} "
                    f"tempo={tempo['tempo']}"
                )
        if track["loops"]:
            emit("  loops:")
            for loop in track["loops"]:
                emit(f"    tick={loop['tick']} value=0x{loop['value']:02X}")
        if track["cue_points"]:
            emit("  cue_points:")
            for cue in track["cue_points"]:
                emit(f"    tick={cue['tick']} value=0x{cue['value']:02X}")
        if track["machine_blocks"]:
            emit("  machine_blocks:")
            for block in track["machine_blocks"]:
                emit(
                    "    "
                    f"tick={block['tick']} "
                    f"cmd={block['command']} "
                    f"len={block['payload_length']} "
                    f"payload={block['payload_hex']}"
                )
        emit("  first events:")
        for event in track["first_events"][:12]:
            if "command" in event:
                emit(
                    "    "
                    f"tick={event['tick']} delta={event['delta']} "
                    f"FF {event['command']:02X} {event['name']} "
                    f"value=0x{event['value']:02X}"
                )
            else:
                emit(
                    "    "
                    f"tick={event['tick']} delta={event['delta']} "
                    f"note voice={event['voice']} "
                    f"pitch=0x{event['note']:02X} "
                    f"gate=0x{event['gate']:02X} "
                    f"vel={event['velocity']}"
                )
        emit("")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path, help="Path to .mld file")
    parser.add_argument("--json", action="store_true", help="Print JSON instead of human text")
    args = parser.parse_args()

    summary = parse_mld(args.path)
    if args.json:
        encoding = sys.stdout.encoding or "utf-8"
        text = json.dumps(summary, ensure_ascii=False, indent=2)
        sys.stdout.buffer.write((text + "\n").encode(encoding, "backslashreplace"))
    else:
        print_human(summary)


if __name__ == "__main__":
    main()
