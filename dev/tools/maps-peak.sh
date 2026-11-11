# maps-peak.sh PID [SECONDS=1800]: run as the dev app (run-as ... sh, cwd = its data dir). Once a second the QEMU's
# kernel mappings (all, and reims' views of guest RAM: memfd:dram) to files/run/maps-sample.txt; whenever the count
# passes the last dump by 1500, a copy of /proc/PID/maps to files/run/maps-peak.txt (so the copy is within 1500 of the
# peak). Stops with QEMU.
P=$1
END=$(( $(date +%s) + ${2:-1800} ))
MAX=0
rm -f files/run/maps-sample.txt files/run/maps-peak.txt files/run/maps-peak.n
while [ -d /proc/$P ] && [ $(date +%s) -lt $END ]; do
    L=$(awk -v t=$(date +%s) '{ n++ } /memfd:dram/ { d++ } END { printf "%s %d %d", t, n, d }' /proc/$P/maps)
    echo "$L" >> files/run/maps-sample.txt
    C=${L#* }
    C=${C%% *}
    if [ "${C:-0}" -gt $((MAX + 1500)) ]; then
        MAX=$C
        cat /proc/$P/maps > files/run/maps-peak.txt
        echo "$L" > files/run/maps-peak.n
    fi
    sleep 1
done
