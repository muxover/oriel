#!/system/bin/sh
# Oriel keeper, started as root by Oriel on TVs that allow it. Keeps the maker's remote mouse mode off
# (Hikeen boards), restarts Oriel Mirror if the system ever stops it, and draws menus at a lower size
# on slow graphics chips without saving that size: a saved size takes effect part-way through booting
# and throws the boot animation off-centre.
pidf=/data/local/tmp/tvkeeper.pid
if [ -f $pidf ] && grep -q tvkeeper /proc/$(cat $pidf)/cmdline 2>/dev/null; then exit 0; fi
echo $$ > $pidf
# Menu size, if the installer asked for one: "1280x720 160" in $sizef. Applied once the boot animation
# is over, then the display settings file is put back as it was, so the next boot starts at full size.
sizef=/data/local/tmp/oriel-size
disp=/data/system/display_settings.xml
clean=/data/local/tmp/oriel-display-clean.xml
sized=0
apply_size() {
  [ -f $sizef ] || return
  [ "$(getprop sys.boot_completed)" = 1 ] && [ "$(getprop init.svc.bootanim)" != running ] || return
  # Wait until the home screen is drawn: changing the size while the boot animation's last frame is still
  # on screen scales it into the corner. After two minutes it is applied anyway.
  if [ $(cut -d. -f1 /proc/uptime) -lt 120 ] && ! dumpsys window | grep -q 'mCurrentFocus=.*HomeActivity'; then return; fi
  sleep 0.3
  if [ -f $disp ]; then cat $disp > $clean; else rm -f $clean; fi
  set -- $(cat $sizef)
  wm size "$1"; wm density "$2"
  sleep 1
  keep_clean
  sized=1
  log -t Oriel "menu size $1 at density $2"
}
# The system rewrites the file when display settings change; put the saved one back each time.
keep_clean() {
  # wm size also saves the size in two settings that Android applies at the end of the next boot, while
  # the boot animation is still on screen; deleting them keeps the size for now only.
  [ "$(settings get global display_size_forced)" = null ] || settings delete global display_size_forced >/dev/null
  [ "$(settings get secure display_density_forced)" = null ] || settings delete secure display_density_forced >/dev/null
  if [ -f $clean ]; then cmp -s $clean $disp || cat $clean > $disp; else rm -f $disp; fi
}

# The TV's own remote buttons: the system logs each one, and the maker's panels for them are switched
# off by the installer, so Oriel's screens open instead (Source, Sleep, a picture button, holding Power).
hotkeys() {
  # Oriel installed as part of the system reads the log itself (READ_LOGS); only one of them may.
  for p in com.zeasn.launcher com.muxover.oriel; do
    pm list packages -s 2>/dev/null | grep -qx "package:$p" && return
  done
  pkill -f 'logcat -v brief -s PhoneWindowManagerEx' 2>/dev/null
  logcat -v brief -s PhoneWindowManagerEx:D WindowManager:D 2>/dev/null | while read -r line; do
    key=""
    case "$line" in
      *"handleKeyEvent  keyCode=2001, down=true"*) key=2001 ;;
      *"handleKeyEvent  keyCode=2010, down=true"*) key=2010 ;;
      *"handleKeyEvent  keyCode=223, down=true"*) key=223 ;;
      *startLongPowerMenu*) key=-1 ;;
    esac
    [ -n "$key" ] && [ -f /data/local/tmp/oriel-home ] || continue
    am start -n "$(cat /data/local/tmp/oriel-home)/com.muxover.oriel.home.HotkeyActivity" --ei key $key >/dev/null 2>&1
  done
}
hotkeys &

# Oriel's home stays black until this size is in place, so the switch after a restart is never seen.
[ -f $sizef ] && setprop debug.oriel.menu_size "$(cat $sizef)"

n=0
while :; do
  [ $sized = 1 ] || apply_size
  # read with the shell's own read: no program is started for it every second, all day
  irm=0
  read -r irm < /sys/module/hid/parameters/ir_to_mouse 2>/dev/null
  if [ "$irm" = 1 ] || [ "$(getprop sys.current.mouse)" = true ]; then
    echo 0 > /sys/module/hid/parameters/ir_to_mouse
    setprop sys.current.mouse false
    setprop sys.current.mouse.launcher false
    cmd settings put system com.hikeen.notifyMouseChange 0
    log -t Oriel "mouse mode turned off"
  fi
  n=$((n + 1))
  if [ $n -ge 15 ]; then
    n=0
    [ $sized = 1 ] && keep_clean
    if ! pidof com.muxover.oriel.mirror >/dev/null; then
      am start-foreground-service -n com.muxover.oriel.mirror/io.github.jqssun.airplay.service.AirPlayService -a io.github.jqssun.airplay.START_SERVER >/dev/null 2>&1
      log -t Oriel "mirror receiver started"
    fi
  fi
  sleep 1
done
