#!/system/bin/sh
# Puts the system partition back the way the maker shipped it, from the journal of every change Oriel kept:
# each maker folder Oriel moved out goes back to where it was, and every file Oriel added is taken out.
# Oriel's own folders that were moved aside during an update are deleted rather than put back.
# The watchdog stays in place until the installer has seen Android start. Run as root; restart afterwards.
journal=/data/local/tmp/oriel-journal
store=/data/local/tmp/oriel-removed
ours() {
  case "$1" in
    /system/priv-app/OrielHome*|/system/product/overlay/OrielFramework*|/system/etc/permissions/privapp-permissions-oriel.xml) return 0 ;;
  esac
  return 1
}
[ -s $journal ] || { echo "no journal: nothing to put back"; exit 2; }
mount -o rw,remount / || { echo "could not open the system for writing"; exit 1; }
tac $journal | while read -r from to; do
  [ -n "$from" ] || continue
  if [ "$to" = "-" ]; then
    rm -rf "$from" && echo "took out $from"
  elif ours "$from"; then
    rm -rf "$to"
  elif [ -e "$to" ]; then
    if [ -e "$from" ]; then echo "kept $from (already there)"; continue; fi
    mkdir -p "$(dirname "$from")"
    if mv "$to" "$from"; then
      chown -R 0:0 "$from"; chcon -R u:object_r:system_file:s0 "$from"
      echo "put back $from"
    else
      echo "FAILED $from"
    fi
  else
    echo "MISSING $to (for $from)"
  fi
done
# Anything Oriel added outside the journal (the first system install, before the journal existed).
for f in /system/priv-app/OrielHome /system/etc/permissions/privapp-permissions-oriel.xml /system/product/overlay/OrielFramework; do
  [ -e "$f" ] && rm -rf "$f" && echo "took out $f"
done
# The watchdog and these helpers stay until the TV has started cleanly; the installer takes them out then.
sync
mount -o ro,remount / 2>/dev/null
rm -f $journal /data/local/tmp/oriel-pending /data/local/tmp/oriel-watchdog-last
rmdir $store 2>/dev/null
echo done
