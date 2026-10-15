f=shared_prefs/settings.xml
if [ ! -f "$f" ]; then
  mkdir -p shared_prefs
  printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <boolean name=\"onboardDone\" value=\"true\" />\n</map>\n" > "$f"
elif [ -f "$f" ] && ! grep -q onboardDone "$f"; then
  sed -i 's#</map>#    <boolean name="onboardDone" value="true" />\n</map>#' "$f"
fi
