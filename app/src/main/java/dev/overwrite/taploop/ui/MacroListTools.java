package dev.overwrite.taploop.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import dev.overwrite.taploop.R;
import dev.overwrite.taploop.io.MacroIO;
import dev.overwrite.taploop.model.Macro;
import dev.overwrite.taploop.model.MacroStore;
import dev.overwrite.taploop.service.TapService;

/**
 * Search / sort / folder filter above the macro list, the per-macro menu,
 * and the export / import / backup / share flows.
 */
class MacroListTools {
    private static final int REQ_EXPORT = 41;
    private static final int REQ_IMPORT = 42;
    private static final int REQ_BACKUP = 43;
    private static final int REQ_RESTORE = 44;

    // over this, sharing as text gets cut off by some apps, send a file instead
    private static final int MAX_SHARE_TEXT = 200_000;

    private final Activity act;
    private final Runnable onChange;
    private final SharedPreferences prefs;
    private final EditText search;
    private final Button folderBtn, sortBtn;

    private String query = "";
    private boolean byName;
    private String folder;
    private final TreeSet<String> folders = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    MacroListTools(Activity act, Runnable onChange) {
        this.act = act;
        this.onChange = onChange;
        prefs = act.getSharedPreferences("macro_list", Context.MODE_PRIVATE);
        byName = prefs.getBoolean("byName", false);
        folder = prefs.getString("folder", "");

        search = act.findViewById(R.id.macro_search);
        folderBtn = act.findViewById(R.id.macro_folder);
        sortBtn = act.findViewById(R.id.macro_sort);
        Button more = act.findViewById(R.id.macro_more);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                onChange.run();
            }
        });
        sortBtn.setOnClickListener(v -> {
            byName = !byName;
            prefs.edit().putBoolean("byName", byName).apply();
            onChange.run();
        });
        folderBtn.setOnClickListener(this::pickFolderFilter);
        more.setOnClickListener(this::showListMenu);
    }

    /** filters and sorts what MacroStore gave us (newest first) */
    List<Macro> filter(List<Macro> all) {
        folders.clear();
        for (Macro m : all) if (!m.folder.isEmpty()) folders.add(m.folder);
        if (!folder.isEmpty() && !folders.contains(folder)) setFolder("");

        List<Macro> out = new ArrayList<>();
        for (Macro m : all) {
            if (!folder.isEmpty() && !folder.equalsIgnoreCase(m.folder)) continue;
            if (!query.isEmpty() && !m.name.toLowerCase(Locale.ROOT).contains(query)
                    && !m.folder.toLowerCase(Locale.ROOT).contains(query)) continue;
            out.add(m);
        }
        if (byName) out.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));

        sortBtn.setText(byName ? "A–Z" : "Recent");
        folderBtn.setText(folder.isEmpty() ? "All" : folder);
        folderBtn.setVisibility(folders.isEmpty() ? View.GONE : View.VISIBLE);
        return out;
    }

    boolean isFiltering() {
        return !query.isEmpty() || !folder.isEmpty();
    }

    private void setFolder(String f) {
        folder = f;
        prefs.edit().putString("folder", f).apply();
    }

    private void pickFolderFilter(View anchor) {
        PopupMenu pm = new PopupMenu(act, anchor);
        List<String> list = new ArrayList<>(folders);
        pm.getMenu().add(0, 0, 0, "All macros");
        for (int i = 0; i < list.size(); i++) pm.getMenu().add(0, i + 1, i + 1, list.get(i));
        pm.setOnMenuItemClickListener(item -> {
            int i = item.getItemId();
            setFolder(i == 0 ? "" : list.get(i - 1));
            onChange.run();
            return true;
        });
        pm.show();
    }

    // list wide actions

    private void showListMenu(View anchor) {
        PopupMenu pm = new PopupMenu(act, anchor);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "Import files");
        m.add(0, 2, 1, "Paste JSON");
        m.add(0, 3, 2, "Back up all");
        m.add(0, 4, 3, "Restore backup");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: openDoc(REQ_IMPORT, true); break;
                case 2: pasteDialog(); break;
                case 3: backup(); break;
                case 4: openDoc(REQ_RESTORE, false); break;
            }
            return true;
        });
        pm.show();
    }

    private void openDoc(int req, boolean multi) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                // lots of file managers report .json as octet-stream, so take anything and check it
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multi);
        launch(i, req);
    }

    private void createDoc(int req, String name) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(MacroIO.MIME)
                .putExtra(Intent.EXTRA_TITLE, name);
        launch(i, req);
    }

    private void launch(Intent i, int req) {
        try {
            act.startActivityForResult(i, req);
        } catch (android.content.ActivityNotFoundException e) {
            toast("No file picker on this device");
        }
    }

    private void backup() {
        if (MacroStore.all(act).isEmpty()) {
            toast("Nothing to back up yet");
            return;
        }
        createDoc(REQ_BACKUP, MacroIO.backupName());
    }

    private void pasteDialog() {
        EditText field = new EditText(act);
        field.setHint("Paste macro JSON here");
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setMinLines(3);
        field.setMaxLines(8);
        field.setGravity(Gravity.TOP | Gravity.START);
        ClipboardManager cm = act.getSystemService(ClipboardManager.class);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(act);
            if (t != null && t.toString().trim().startsWith("{")) field.setText(t);
        }
        new AlertDialog.Builder(act)
                .setTitle("Import from text")
                .setView(wrap(field))
                .setPositiveButton("Import", (d, w) -> {
                    String text = field.getText().toString();
                    try {
                        MacroIO.Result r = MacroIO.parse(text);
                        int n = MacroIO.saveAll(act, r.macros);
                        toast(importedMsg(n, r.skipped));
                        onChange.run();
                    } catch (IOException e) {
                        toast(e.getMessage());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // per macro menu

    void showItemMenu(View anchor, Macro m) {
        PopupMenu pm = new PopupMenu(act, anchor);
        Menu menu = pm.getMenu();
        menu.add(0, 1, 0, "Rename");
        menu.add(0, 2, 1, "Duplicate");
        menu.add(0, 3, 2, m.folder.isEmpty() ? "Move to folder" : "Folder: " + m.folder);
        menu.add(0, 4, 3, "Export file");
        menu.add(0, 5, 4, "Share file");
        menu.add(0, 6, 5, "Share as text");
        menu.add(0, 7, 6, "Delete");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: rename(m); break;
                case 2: duplicate(m); break;
                case 3: moveToFolder(m); break;
                case 4:
                    prefs.edit().putString("pendingExport", m.id).apply();
                    createDoc(REQ_EXPORT, MacroIO.fileName(m));
                    break;
                case 5: shareFile(m); break;
                case 6: shareText(m); break;
                case 7: confirmDelete(m); break;
            }
            return true;
        });
        pm.show();
    }

    private void rename(Macro m) {
        EditText field = new EditText(act);
        field.setSingleLine(true);
        field.setText(m.name);
        field.setSelection(field.length());
        new AlertDialog.Builder(act)
                .setTitle("Rename")
                .setView(wrap(field))
                .setPositiveButton("Save", (d, w) -> {
                    String n = field.getText().toString().trim();
                    if (n.isEmpty() || n.equals(m.name)) return;
                    m.name = n;
                    MacroStore.save(act, m);
                    reloadActive(m.id);
                    onChange.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void duplicate(Macro m) {
        try {
            Macro copy = MacroIO.duplicate(act, m);
            toast("Saved as \"" + copy.name + "\"");
        } catch (Exception e) {
            toast("Couldn't copy it");
        }
        onChange.run();
    }

    private void moveToFolder(Macro m) {
        List<String> items = new ArrayList<>(folders);
        items.remove(m.folder);
        items.add("New folder…");
        if (!m.folder.isEmpty()) items.add("No folder");
        new AlertDialog.Builder(act)
                .setTitle("Move \"" + m.name + "\" to")
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    String pick = items.get(w);
                    if (pick.equals("New folder…")) newFolder(m);
                    else setMacroFolder(m, pick.equals("No folder") ? "" : pick);
                })
                .show();
    }

    private void newFolder(Macro m) {
        EditText field = new EditText(act);
        field.setSingleLine(true);
        field.setHint("Folder name");
        new AlertDialog.Builder(act)
                .setTitle("New folder")
                .setView(wrap(field))
                .setPositiveButton("Move", (d, w) -> {
                    String f = MacroIO.cleanFolder(field.getText().toString());
                    if (!f.isEmpty()) setMacroFolder(m, f);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setMacroFolder(Macro m, String f) {
        // reuse the existing spelling if the folder is already there
        for (String e : folders) if (e.equalsIgnoreCase(f)) f = e;
        m.folder = f;
        MacroStore.save(act, m);
        reloadActive(m.id);
        onChange.run();
    }

    private void confirmDelete(Macro m) {
        new AlertDialog.Builder(act)
                .setTitle("Delete \"" + m.name + "\"?")
                .setMessage("This can't be undone. Export it first if you might want it back.")
                .setPositiveButton("Delete", (d, w) -> {
                    MacroStore.delete(act, m.id);
                    TapService svc = TapService.get();
                    if (svc != null && !svc.isBusy() && svc.getActive() != null
                            && m.id.equals(svc.getActive().id)) {
                        svc.setActive(null);
                    }
                    onChange.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void reloadActive(String id) {
        TapService svc = TapService.get();
        if (svc != null && !svc.isBusy() && svc.getActive() != null && id.equals(svc.getActive().id)) {
            svc.setActive(MacroStore.load(act, id));
        }
    }

    private void shareFile(Macro m) {
        try {
            Uri uri = MacroIO.shareFile(act, MacroIO.fileName(m), MacroIO.toText(m));
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType(MacroIO.MIME)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "TapLoop macro: " + m.name)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // chooser only passes the grant on when the uri is also in the clip data
            send.setClipData(ClipData.newRawUri(m.name, uri));
            act.startActivity(Intent.createChooser(send, "Share macro"));
        } catch (Exception e) {
            toast("Couldn't share it");
        }
    }

    private void shareText(Macro m) {
        String text;
        try {
            text = MacroIO.toText(m);
        } catch (Exception e) {
            toast("Couldn't share it");
            return;
        }
        if (text.length() > MAX_SHARE_TEXT) {
            toast("Too big for text (image checks), sending a file");
            shareFile(m);
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "TapLoop macro: " + m.name)
                .putExtra(Intent.EXTRA_TEXT, text);
        act.startActivity(Intent.createChooser(send, "Share macro"));
    }

    // results from the document pickers

    boolean onResult(int req, int res, Intent data) {
        if (req != REQ_EXPORT && req != REQ_IMPORT && req != REQ_BACKUP && req != REQ_RESTORE) {
            return false;
        }
        String pending = prefs.getString("pendingExport", null);
        if (req == REQ_EXPORT) prefs.edit().remove("pendingExport").apply();
        if (res != Activity.RESULT_OK || data == null) return true;

        if (req == REQ_EXPORT || req == REQ_BACKUP) {
            Uri uri = data.getData();
            if (uri == null) return true;
            Macro one = req == REQ_EXPORT ? MacroStore.load(act, pending) : null;
            if (req == REQ_EXPORT && one == null) {
                toast("That macro is gone");
                return true;
            }
            work(() -> {
                List<Macro> list;
                if (one != null) {
                    list = new ArrayList<>();
                    list.add(one);
                } else {
                    list = MacroStore.all(act);
                }
                MacroIO.write(act.getContentResolver(), uri, MacroIO.toText(list, true));
                return one != null ? "Exported" : "Backed up " + count(list.size());
            }, null);
            return true;
        }

        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri u = data.getClipData().getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return true;
        if (req == REQ_RESTORE) restore(uris.get(0));
        else importFiles(uris);
        return true;
    }

    private void importFiles(List<Uri> uris) {
        work(() -> {
            List<Macro> found = new ArrayList<>();
            int skipped = 0, badFiles = 0;
            String lastError = null;
            for (Uri u : uris) {
                try {
                    MacroIO.Result r = MacroIO.parse(MacroIO.read(act.getContentResolver(), u));
                    found.addAll(r.macros);
                    skipped += r.skipped;
                } catch (IOException e) {
                    badFiles++;
                    lastError = e.getMessage();
                }
            }
            if (found.isEmpty()) {
                return uris.size() == 1 ? lastError : "None of those files had macros";
            }
            int n = MacroIO.saveAll(act, found);
            String msg = importedMsg(n, skipped);
            if (badFiles > 0) msg += ", " + badFiles + (badFiles == 1 ? " file" : " files") + " not readable";
            return msg;
        }, onChange);
    }

    private void restore(Uri uri) {
        work(() -> {
            MacroIO.Result r = MacroIO.parse(MacroIO.read(act.getContentResolver(), uri));
            act.runOnUiThread(() -> {
                if (act.isFinishing() || act.isDestroyed()) return;
                int have = MacroStore.all(act).size();
                AlertDialog.Builder b = new AlertDialog.Builder(act)
                        .setTitle("Restore " + count(r.macros.size()) + "?")
                        .setNegativeButton("Cancel", null);
                if (have == 0) {
                    b.setPositiveButton("Restore", (d, w) -> doRestore(r, false));
                } else {
                    b.setMessage("You have " + count(have) + " now. Keep them and add these, "
                            + "or replace everything with the backup?")
                            .setPositiveButton("Add", (d, w) -> doRestore(r, false))
                            .setNeutralButton("Replace all", (d, w) -> confirmReplace(r, have));
                }
                b.show();
            });
            return null;
        }, null);
    }

    private void confirmReplace(MacroIO.Result r, int have) {
        new AlertDialog.Builder(act)
                .setTitle("Replace all macros?")
                .setMessage("Your current " + count(have) + " will be deleted.")
                .setPositiveButton("Replace", (d, w) -> doRestore(r, true))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doRestore(MacroIO.Result r, boolean replace) {
        TapService svc = TapService.get();
        if (replace && svc != null && svc.isBusy()) {
            toast("Stop the current run first");
            return;
        }
        if (replace) {
            for (Macro m : MacroStore.all(act)) MacroStore.delete(act, m.id);
            if (svc != null) svc.setActive(null);
        }
        int n = MacroIO.saveAll(act, r.macros);
        toast("Restored " + count(n) + (r.skipped > 0 ? ", skipped " + r.skipped + " broken" : ""));
        onChange.run();
    }

    // helpers

    private interface Job {
        String run() throws Exception;
    }

    /** runs file io off the main thread, then toasts the result and refreshes */
    private void work(Job job, Runnable after) {
        new Thread(() -> {
            String msg;
            try {
                msg = job.run();
            } catch (IOException e) {
                msg = e.getMessage();
            } catch (Exception e) {
                msg = "Something went wrong with that file";
            }
            String m = msg;
            act.runOnUiThread(() -> {
                if (act.isDestroyed()) return;
                if (m != null) toast(m);
                if (after != null) after.run();
            });
        }, "macro-io").start();
    }

    private static String importedMsg(int n, int skipped) {
        String s = "Imported " + count(n);
        if (skipped > 0) s += ", skipped " + skipped + " broken";
        return s;
    }

    private static String count(int n) {
        return n == 1 ? "1 macro" : n + " macros";
    }

    private View wrap(View v) {
        FrameLayout f = new FrameLayout(act);
        int p = Math.round(20 * act.getResources().getDisplayMetrics().density);
        f.setPadding(p, p / 2, p, 0);
        f.addView(v);
        return f;
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_LONG).show();
    }
}
