"""Offline native maintenance; no implicit replacement of live operator data."""

import json
from pathlib import Path
from typing import Annotated

import typer

from app.maintenance import backup

app = typer.Typer(help="Encrypted consistent SQLite backups; keep the key separately.")


@app.command("keygen")
def keygen(path: Path):
    backup.keygen(path)
    print("Private backup key created. Store a separate offline copy.")


@app.command("create")
def create(database: Path, output: Path, key_file: Path, include: Annotated[list[Path] | None, typer.Option()] = None):
    print(json.dumps(backup.create(database, output, key_file, include=include or ())))


@app.command("restore")
def restore(archive: Path, output_dir: Path, key_file: Path):
    print(json.dumps(backup.restore(archive, output_dir, key_file)))


@app.command("verify")
def verify(archive: Path, key_file: Path):
    print(json.dumps(backup.verify(archive, key_file)))


if __name__ == "__main__":
    app()
