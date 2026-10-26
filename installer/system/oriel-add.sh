#!/system/bin/sh
# Adds files to the system partition and lists each one for the boot watchdog, which takes them out
# again if Android then fails to start. Usage (as root): oriel-add.sh <source> <target> [<source> <target> ...]
pending=/data/local/tmp/oriel-pending
mount -o rw,remount / || { echo "could not open the system for writing"; exit 1; }
while [ $# -ge 2 ]; do
  src=$1; dst=$2; shift 2
  if [ -e "$dst" ]; then echo "skip $dst (already there)"; continue; fi
  mkdir -p "$(dirname "$dst")"
  if cp "$src" "$dst"; then
    chmod 644 "$dst"; chown 0:0 "$dst"; chcon u:object_r:system_file:s0 "$dst"
    echo "$dst -" >> $pending
    echo "added $dst"
  else
    echo "FAILED $dst"
  fi
done
sync
mount -o ro,remount / || echo "system stays writable until restart"
