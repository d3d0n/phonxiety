package com.phonxiety.bridge.data.updates

/**
 * The detached root script. The app publishes it under [RollbackFiles.DIRECTORY] and starts it with
 * `nohup … &`; everything after the start happens outside the app's process, because
 * `pm uninstall-system-updates` on this app's own package freezes and kills that process.
 *
 * `§` stands for the shell's `$` in [RAW]: it keeps Kotlin from interpolating the script.
 */
object RollbackScript {
    private val RAW: String = """
        #!/system/bin/sh
        # Phonxiety rollback helper; written by the app (RollbackScript.kt). Started detached,
        # because `pm uninstall-system-updates` on the app's own package freezes and kills the
        # app's process: the removal, its verification, the journal entry and the reboot must
        # survive it.
        set -u

        PKG="§{PHONXIETY_ROLLBACK_PACKAGE:-com.phonxiety.bridge}"
        DIR="§{PHONXIETY_ROLLBACK_DIR:-/data/adb/phonxiety}"
        JOURNAL="§DIR/rollback.json"

        # §1: removed|already_absent|failed|unknown. Rewrites the journal's step in place, atomically,
        # and fails when the new step did not reach the disk: a reboot must never activate an older
        # module behind a journal that still says module_staged.
        record() {
            tmp="§JOURNAL.tmp"
            if ! sed "s|\"step\":[[:space:]]*\"[a-z_]*\"|\"step\": \"§1\"|" "§JOURNAL" >"§tmp" 2>/dev/null; then
                rm -f "§tmp"
                return 1
            fi
            if ! mv -f "§tmp" "§JOURNAL"; then
                rm -f "§tmp"
                return 1
            fi
            sync
        }

        # 0: a data-partition copy exists, 1: only the system image's copy remains, 2: cannot tell.
        data_apk_state() {
            paths=§(pm path "§PKG" 2>/dev/null) || return 2
            case "§paths" in
                *"/data/app/"*) return 0 ;;
                "") return 2 ;;
                *) return 1 ;;
            esac
        }

        data_apk_state
        before=§?
        case "§before" in
            1)
                record already_absent || exit 1
                reboot
                exit 0 ;;
            2)
                record unknown
                exit 1 ;;
        esac

        output=§(pm uninstall-system-updates "§PKG" 2>&1)
        status=§?
        echo "§output"

        # AOSP returns 1 and prints "Success" when the update was removed; 0 means it reported a
        # failure. §? is not ShellOutput.ok, so the two must never be confused.
        case "§output:§status" in
            *Success*:1) ;;
            *)
                record failed
                exit 1 ;;
        esac

        data_apk_state
        after=§?
        case "§after" in
            0)
                record failed
                exit 1 ;;
            1)
                record removed || exit 1
                reboot
                exit 0 ;;
            *)
                record unknown
                exit 1 ;;
        esac
"""

    val TEXT: String = RAW.trimIndent().replace('§', '$') + "\n"
}
