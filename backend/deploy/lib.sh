# Shared helpers for the operator-run deployment scripts. Sourced, not executed.
# shellcheck shell=bash

DRY_RUN=0
POSITIONAL=()

parse_args() {
  POSITIONAL=()
  local a
  for a in "$@"; do
    case "$a" in
      --dry-run) DRY_RUN=1 ;;
      -h | --help) usage; exit 0 ;;
      -*) echo "unknown option: $a" >&2; usage >&2; exit 2 ;;
      *) POSITIONAL+=("$a") ;;
    esac
  done
}

# Print one shell-quoted command line.
print_cmd() {
  local first=1 w
  for w in "$@"; do
    [ "$first" = 1 ] || printf ' '
    first=0
    case "$w" in
      '' | *[!A-Za-z0-9_@%+=:,./-]*) printf "'%s'" "${w//\'/\'\\\'\'}" ;;
      *) printf '%s' "$w" ;;
    esac
  done
  printf '\n'
}

# Run a command, or only print it with --dry-run.
run() {
  if [ "$DRY_RUN" = 1 ]; then
    print_cmd "$@"
  else
    print_cmd "$@" >&2
    "$@"
  fi
}

# ensure <describe command words> :: <create command words>
# Creates the resource only if the describe command fails. With --dry-run only the create
# command is printed (it is skipped at run time when the resource already exists).
ensure() {
  local desc=() create=() seen=0 w
  for w in "$@"; do
    if [ "$w" = "::" ] && [ "$seen" = 0 ]; then
      seen=1
    elif [ "$seen" = 0 ]; then
      desc+=("$w")
    else
      create+=("$w")
    fi
  done
  if [ "$DRY_RUN" = 1 ]; then
    echo "# create if missing:"
    print_cmd "${create[@]}"
  elif "${desc[@]}" >/dev/null 2>&1; then
    echo "exists, skipping: ${desc[*]}" >&2
  else
    run "${create[@]}"
  fi
}

require_gcloud() {
  if [ "$DRY_RUN" = 0 ] && ! command -v gcloud >/dev/null 2>&1; then
    echo "gcloud is required (not needed with --dry-run)" >&2
    exit 1
  fi
}
