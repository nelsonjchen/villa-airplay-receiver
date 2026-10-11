# Working rules

- This is the Villa AirPlay Receiver app fork. Investigation notes and rollback artifacts live in the separate local airplay-receiver-lab workspace; consult its docs before device experiments.
- Distinguish measurements, source observations, hypotheses, and upstream reports.
- Back up settings and installed APKs before changing them. Record rollback.
- Never equate advertising HEVC with sending HEVC, or TCP with proven latency causation.
- Keep Apple binaries/disassemblies, raw logs, device identifiers and downloaded APKs in ignored `.local/`.
- Use uv for Python and pnpm when relevant. Shell is zsh; avoid special parameter names such as status.
- Do not modify unrelated development tools or system settings.
- Do not publish upstream comments or issues without explicit user authorization.
