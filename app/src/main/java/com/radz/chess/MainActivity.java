package com.radz.chess;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements ChessView.Listener {

    private static final int BG = Color.rgb(32, 30, 28);
    private static final int ACCENT = Color.rgb(230, 120, 20);

    private final Board board = new Board();
    private final Engine engine = new Engine();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> san = new ArrayList<>();

    private ChessView view;
    private TextView status, moves, capTop, capBottom;
    private HorizontalScrollView movesScroll;

    private int playerColour = Board.WHITE;
    private int level = 2;
    private boolean thinking = false;
    private boolean gameOver = false;
    private int gameId = 0; // bumps on new game / undo so stale AI results are ignored

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
        restore();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(8), dp(12), dp(8), dp(12));

        TextView title = new TextView(this);
        title.setText("Chess");
        title.setTextColor(ACCENT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(17);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(6), 0, dp(6));
        root.addView(status);

        capTop = capturedRow();
        root.addView(capTop);

        view = new ChessView(this);
        view.setBoard(board);
        view.setListener(this);
        root.addView(view, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        capBottom = capturedRow();
        root.addView(capBottom);

        movesScroll = new HorizontalScrollView(this);
        moves = new TextView(this);
        moves.setTextColor(Color.rgb(200, 200, 200));
        moves.setTextSize(15);
        moves.setTypeface(Typeface.MONOSPACE);
        moves.setPadding(dp(4), dp(8), dp(4), dp(8));
        moves.setSingleLine(true);
        movesScroll.addView(moves);
        root.addView(movesScroll);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(8), 0, 0);
        buttons.addView(button("New", v -> showNewGameDialog()));
        buttons.addView(button("Undo", v -> undo()));
        buttons.addView(button("Hint", v -> hint()));
        buttons.addView(button("Flip", v -> { view.setFlipped(!view.isFlipped()); updateCaptured(); }));
        root.addView(buttons);

        setContentView(root);
    }

    private TextView capturedRow() {
        TextView t = new TextView(this);
        t.setTextColor(Color.rgb(220, 220, 220));
        t.setTextSize(18);
        t.setPadding(dp(4), dp(2), dp(4), dp(2));
        t.setMinHeight(dp(28));
        return t;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(60, 56, 52));
        bg.setStroke(dp(1), ACCENT);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        return b;
    }

    // ---------------- game flow ----------------

    private void newGame(int colour, int lvl) {
        gameId++;
        engine.abort();
        thinking = false;
        gameOver = false;
        playerColour = colour;
        level = lvl;
        board.reset();
        san.clear();
        view.setBoard(board);
        view.setFlipped(colour == Board.BLACK);
        afterMove();
    }

    private void applyMove(int m) {
        san.add(board.toSan(m));
        board.makeMove(m);
        view.clearSelection();
        afterMove();
    }

    /** Updates status/UI and triggers the computer if it's its turn. */
    private void afterMove() {
        updateMoves();
        updateCaptured();
        String result = gameResult();
        if (result != null) {
            gameOver = true;
            status.setText(result);
            view.setInputEnabled(false);
            view.invalidate();
            save();
            return;
        }
        boolean myTurn = board.side == playerColour;
        view.setInputEnabled(myTurn);
        if (myTurn) {
            status.setText((board.inCheck() ? "Check! " : "") + "Your move (" + Engine.LEVEL_NAMES[level] + ")");
        } else {
            status.setText("Computer is thinking…");
            startComputer();
        }
        view.invalidate();
        save();
    }

    private String gameResult() {
        int[] lm = new int[256];
        if (board.legalMoves(lm) == 0) {
            if (board.inCheck()) {
                return (board.side == playerColour) ? "Checkmate — computer wins" : "Checkmate — you win! 🎉";
            }
            return "Stalemate — draw";
        }
        if (board.insufficientMaterial()) return "Draw — insufficient material";
        if (board.halfmove >= 100) return "Draw — 50-move rule";
        if (board.repetitions() >= 3) return "Draw — threefold repetition";
        return null;
    }

    private void startComputer() {
        thinking = true;
        final int id = gameId;
        final int lvl = level;
        final Board snapshot = new Board();
        Engine.copy(board, snapshot);
        worker.execute(() -> {
            long t0 = System.currentTimeMillis();
            int m = engine.findMove(snapshot, lvl);
            long wait = 350 - (System.currentTimeMillis() - t0); // small pause so instant replies feel natural
            if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException ignored) { }
            ui.post(() -> {
                if (id != gameId) return;
                thinking = false;
                if (m != 0 && isLegal(m)) applyMove(m);
            });
        });
    }

    private boolean isLegal(int m) {
        int[] lm = new int[256];
        int n = board.legalMoves(lm);
        for (int i = 0; i < n; i++) if (lm[i] == m) return true;
        return false;
    }

    @Override
    public void onUserMove(List<Integer> candidates) {
        if (thinking || gameOver || board.side != playerColour) return;
        if (candidates.size() == 1) { applyMove(candidates.get(0)); return; }
        // Promotion choice
        String[] names = {"Queen", "Rook", "Bishop", "Knight"};
        int[] types = {Board.QUEEN, Board.ROOK, Board.BISHOP, Board.KNIGHT};
        new AlertDialog.Builder(this)
                .setTitle("Promote to")
                .setItems(names, (d, which) -> {
                    for (int m : candidates) if (Board.promo(m) == types[which]) { applyMove(m); return; }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void undo() {
        if (board.ply == 0) return;
        gameId++;
        engine.abort();
        thinking = false;
        gameOver = false;
        // Take back to the last position where it was the player's turn
        do {
            board.unmakeMove();
            san.remove(san.size() - 1);
        } while (board.ply > 0 && board.side != playerColour);
        view.clearSelection();
        afterMove(); // if the player is black and we're back at the start, the computer replays its first move
    }

    private void hint() {
        if (thinking || gameOver || board.side != playerColour) return;
        status.setText("Thinking of a hint…");
        view.setInputEnabled(false);
        final int id = gameId;
        final int plyAt = board.ply;
        final Board snapshot = new Board();
        Engine.copy(board, snapshot);
        thinking = true;
        worker.execute(() -> {
            int m = new Engine().findMove(snapshot, 3);
            ui.post(() -> {
                if (id != gameId || plyAt != board.ply) return;
                thinking = false;
                view.setInputEnabled(true);
                status.setText("Hint: " + (m != 0 ? board.toSan(m) : "—"));
                view.setHint(m);
            });
        });
    }

    private void updateMoves() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < san.size(); i++) {
            if (i % 2 == 0) sb.append(i / 2 + 1).append(". ");
            sb.append(san.get(i)).append(' ');
        }
        moves.setText(sb.length() == 0 ? "No moves yet" : sb.toString());
        movesScroll.post(() -> movesScroll.fullScroll(View.FOCUS_RIGHT));
    }

    /** Shows pieces each side has captured plus material lead. */
    private void updateCaptured() {
        int[] start = {0, 8, 2, 2, 2, 1, 1};
        int[] w = new int[7], b = new int[7];
        for (int i = 0; i < 64; i++) {
            int p = board.sq[i];
            if (p > 0) w[p]++; else if (p < 0) b[-p]++;
        }
        StringBuilder byWhite = new StringBuilder(), byBlack = new StringBuilder();
        String glyph = " ♟♞♝♜♛";
        int matW = 0, matB = 0;
        for (int t = 5; t >= 1; t--) {
            for (int k = 0; k < Math.max(0, start[t] - b[t]); k++) byWhite.append(glyph.charAt(t)).append('︎');
            for (int k = 0; k < Math.max(0, start[t] - w[t]); k++) byBlack.append(glyph.charAt(t)).append('︎');
            matW += w[t] * Engine.VAL[t];
            matB += b[t] * Engine.VAL[t];
        }
        int diff = (matW - matB) / 100;
        if (diff > 0) byWhite.append("  +").append(diff);
        if (diff < 0) byBlack.append("  +").append(-diff);
        // Bottom row = side at the bottom of the board
        boolean whiteBottom = !view.isFlipped();
        capBottom.setText(whiteBottom ? byWhite : byBlack);
        capTop.setText(whiteBottom ? byBlack : byWhite);
    }

    private void showNewGameDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        TextView l1 = new TextView(this);
        l1.setText("Play as");
        l1.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(l1);
        RadioGroup colours = new RadioGroup(this);
        String[] cNames = {"White", "Black", "Random"};
        for (int i = 0; i < 3; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(cNames[i]);
            r.setId(100 + i);
            colours.addView(r);
        }
        colours.check(playerColour == Board.WHITE ? 100 : 101);
        box.addView(colours);

        TextView l2 = new TextView(this);
        l2.setText("Difficulty");
        l2.setTypeface(Typeface.DEFAULT_BOLD);
        l2.setPadding(0, dp(12), 0, 0);
        box.addView(l2);
        RadioGroup levels = new RadioGroup(this);
        for (int i = 0; i < Engine.LEVEL_NAMES.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(Engine.LEVEL_NAMES[i]);
            r.setId(200 + i);
            levels.addView(r);
        }
        levels.check(200 + level);
        box.addView(levels);

        new AlertDialog.Builder(this)
                .setTitle("New game")
                .setView(box)
                .setPositiveButton("Start", (d, w) -> {
                    int c = colours.getCheckedRadioButtonId() - 100;
                    int colour = c == 0 ? Board.WHITE : c == 1 ? Board.BLACK : (new Random().nextBoolean() ? Board.WHITE : Board.BLACK);
                    newGame(colour, levels.getCheckedRadioButtonId() - 200);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------- persistence ----------------

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < board.ply; i++) sb.append(board.moveToUci(board.moveAt(i))).append(' ');
        getSharedPreferences("chess", MODE_PRIVATE).edit()
                .putString("moves", sb.toString().trim())
                .putInt("colour", playerColour)
                .putInt("level", level)
                .putBoolean("flipped", view.isFlipped())
                .apply();
    }

    private void restore() {
        SharedPreferences p = getSharedPreferences("chess", MODE_PRIVATE);
        playerColour = p.getInt("colour", Board.WHITE);
        level = Math.max(0, Math.min(4, p.getInt("level", 2)));
        board.reset();
        san.clear();
        String ms = p.getString("moves", "");
        if (!ms.isEmpty()) {
            int[] lm = new int[256];
            for (String u : ms.split(" ")) {
                int n = board.legalMoves(lm), found = 0;
                for (int i = 0; i < n; i++) if (board.moveToUci(lm[i]).equals(u)) { found = lm[i]; break; }
                if (found == 0) break;
                san.add(board.toSan(found));
                board.makeMove(found);
            }
        }
        view.setBoard(board);
        view.setFlipped(p.getBoolean("flipped", playerColour == Board.BLACK));
        afterMove();
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        gameId++;
        engine.abort();
        worker.shutdownNow();
    }
}
