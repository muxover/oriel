#!/system/bin/sh
# Takes system apps out of the system partition without deleting them: each folder moves to
# $store, and the move is listed for the boot watchdog, which puts it back if Android then fails to start.
# Usage (as root): oriel-remove.sh /system/app/Name [/system/priv-app/Other ...]
store=/data/local/tmp/oriel-removed
pending=/data/local/tmp/oriel-pending
mkdir -p $store
mount -o rw,remount / || { echo "could not open the system for writing"; exit 1; }
for from in "$@"; do
  [ -d "$from" ] || { echo "skip $from (not there)"; continue; }
  # A folder moved out before (an older Oriel build, say) keeps its copy; this one gets a numbered name.
  base=$store/$(echo "$from" | sed 's#^/##; s#/#_#g'); to=$base; i=1
  while [ -e "$to" ]; do to=$base.$i; i=$((i + 1)); done
  if mv "$from" "$to"; then
    echo "$from $to" >> $pending
    echo "moved $from"
  else
    echo "FAILED $from"
  fi
done
sync
mount -o ro,remount / || echo "system stays writable until restart"
