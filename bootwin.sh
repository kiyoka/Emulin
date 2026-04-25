echo booting Emulin
if [ -e/etc/fstab ]; then
  /bin/rm /etc/mtab*
  /bin/mount -a
  echo "[mountfs]  "
  /bin/mount
fi
export TERM=pcansi-m
export PS1="(Emulin)\$ "
/bin/ash
