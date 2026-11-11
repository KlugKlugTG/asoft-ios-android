# bar-xy.sh: source it (with ANDROID_SERIAL set). bar_xy DESC prints the centre "x y" of the view in the app's
# window whose content description starts with DESC ("Home", "Side button", "Saves"...), or nothing.
# The user drags the side bar (pref bar-y), so tests read its buttons from the window instead of fixed rows;
# a screenshot finder took iOS 16's battery icon for the bar's first icon (2026-11-07). Nothing printed = no tap.
bar_xy() {
    local xy i
    for i in 1 2 3; do      # a dump taken while a view animates can come back empty: try again
        xy=$(adb exec-out uiautomator dump /dev/tty 2>/dev/null | tr '>' '\n' \
            | grep -m1 -oE "content-desc=\"$1[^\"]*\"[^/]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" \
            | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' \
            | awk 'NF == 4 && $1 ~ /^[0-9]+$/ {print int(($1 + $3) / 2), int(($2 + $4) / 2)}')
        [ -n "$xy" ] && { echo "$xy"; return 0; }
        sleep 1
    done
    return 1
}
