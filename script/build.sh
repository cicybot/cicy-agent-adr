CUR_DIR=
get_cur_dir() {
    # Get the fully qualified path to the script
    case $0 in
        /*)
            SCRIPT="$0"
            ;;
        *)
            PWD_DIR=$(pwd);
            SCRIPT="${PWD_DIR}/$0"
            ;;
    esac
    # Resolve the true real path without any sym links.
    CHANGED=true
    while [ "X$CHANGED" != "X" ]
    do
        # Change spaces to ":" so th`e tokens can be parsed.
        SAFESCRIPT=`echo $SCRIPT | sed -e 's; ;:;g'`
        # Get the real path to this script, resolving any symbolic links
        TOKENS=`echo $SAFESCRIPT | sed -e 's;/; ;g'`
        REALPATH=
        for C in $TOKENS; do
            # Change any ":" in the token back to a space.
            C=`echo $C | sed -e 's;:; ;g'`
            REALPATH="$REALPATH/$C"
            # If REALPATH is a sym link, resolve it.  Loop for nested links.
            while [ -h "$REALPATH" ] ; do
                LS="`ls -ld "$REALPATH"`"
                LINK="`expr "$LS" : '.*-> \(.*\)$'`"
                if expr "$LINK" : '/.*' > /dev/null; then
                    # LINK is absolute.
                    REALPATH="$LINK"
                else
                    # LINK is relative.
                    REALPATH="`dirname "$REALPATH"`""/$LINK"
                fi
            done
        done

        if [ "$REALPATH" = "$SCRIPT" ]
        then
            CHANGED=""
        else
            SCRIPT="$REALPATH"
        fi
    done
    # Change the current directory to the location of the script
    CUR_DIR=$(dirname "${REALPATH}")
}

get_cur_dir
PROJECT_DIR=$(dirname "${CUR_DIR}")
mkdir -p $PROJECT_DIR/build
echo PROJECT_DIR: $PROJECT_DIR
cd $PROJECT_DIR

buildTarget() {
  local target=$1
  echo "[+] BuildTarget $target"
  ./gradlew assembleDebug
  mv app/build/outputs/apk/debug/app-debug.apk "$PROJECT_DIR"/build/app-v0.0.0-"$target".apk
  ls -al "$PROJECT_DIR"/build/
}

targets="arm64 armv7a x86_64"

if [ "$1" = "arm64" ]; then
  target="arm64"
  buildTarget "$target"
elif [ "$1" = "armv7a" ]; then
  target="armv7a"
  buildTarget "$target"
elif [ "$1" = "x86_64" ]; then
  target="x86_64"
  buildTarget "$target"
else
  for target in $targets; do
        buildTarget "$target"
  done
fi


