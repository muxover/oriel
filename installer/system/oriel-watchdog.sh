#!/system/bin/sh
# Runs at every boot, below Android. Each change Oriel makes to the system partition first lists what it
# moves in $pending ("<original path> <where it went>", one per line), and each file it adds as
# "<path> -". If Android then finishes starting
# and keeps running for a minute, the change is kept. If it has not finished after $limit seconds, every
# moved item goes back to where it was, the list is cleared and the TV restarts.
pending=/data/local/tmp/oriel-pending
test=/data/local/tmp/oriel-watchdog-test
# Every change that was kept, for Restore Factory; and the last verdict, for the installer.
journal=/data/local/tmp/oriel-journal
verdict=/data/local/tmp/oriel-watchdog-last
limit=300
[ -f /data/local/tmp/oriel-watchdog-secs ] && limit=$(cat /data/local/tmp/oriel-watchdog-secs)
[ -s $pending ] || exit 0
log -t Oriel "watchdog: checking this boot (limit ${limit}s)"
t=0
while [ $t -lt "$limit" ]; do
  if [ "$(getprop sys.boot_completed)" = 1 ] && [ ! -f $test ]; then
    pid=$(pidof system_server)
    sleep 60
    if [ -n "$pid" ] && [ "$(pidof system_server)" = "$pid" ]; then
      cat $pending >> $journal
      rm -f $pending
      echo kept > $verdict
      log -t Oriel "watchdog: Android started and stayed up, change kept"
      exit 0
    fi
    t=$((t + 60))
    continue
  fi
  sleep 5
  t=$((t + 5))
done
log -t Oriel "watchdog: Android did not finish starting, putting the last change back"
mount -o rw,remount /
# Newest first, so a file that replaced an older one is taken out before the older one goes back.
tac $pending | while read -r from to; do
  if [ "$to" = "-" ]; then
    rm -rf "$from"
    log -t Oriel "watchdog: took out $from"
    continue
  fi
  [ -n "$from" ] && [ -e "$to" ] || continue
  mkdir -p "$(dirname "$from")"
  mv "$to" "$from"
  chown -R 0:0 "$from"
  chcon -R u:object_r:system_file:s0 "$from"
  log -t Oriel "watchdog: put back $from"
done
rm -f $pending $test
echo undone > $verdict
sync
mount -o ro,remount /
setprop sys.powerctl reboot
