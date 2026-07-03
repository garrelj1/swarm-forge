#!/bin/zsh
# Provider-agnostic host notification: terminal bell + best-effort sound.
# Usage: notify-host.sh <needs-input|task-done|error>
# Never fails the caller: swallows all errors and always exits 0, because a
# broken notification must never block or fail an agent's actual turn.

event="${1:-unknown}"

printf '\a'

play_sound() {
  case "$(uname -s 2>/dev/null)" in
    Darwin)
      command -v afplay >/dev/null 2>&1 && afplay /System/Library/Sounds/Ping.aiff >/dev/null 2>&1
      ;;
    *)
      if command -v paplay >/dev/null 2>&1; then
        paplay /usr/share/sounds/freedesktop/stereo/complete.oga >/dev/null 2>&1
      elif command -v aplay >/dev/null 2>&1; then
        aplay /usr/share/sounds/alsa/Front_Center.wav >/dev/null 2>&1
      fi
      ;;
  esac
}

play_sound || true

exit 0
