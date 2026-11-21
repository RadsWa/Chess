package com.radz.chess;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Draws the board and handles tap-to-move input. */
public class ChessView extends View {

    public interface Listener {
        /** Called with all legal moves matching the tapped from/to (several only for promotions). */
        void onUserMove(List<Integer> candidates);
    }

    private static final int LIGHT = Color.rgb(240, 217, 181);
    private static final int DARK = Color.rgb(181, 136, 99);
    private static final int SEL = Color.argb(150, 255, 140, 0);
    private static final int LAST = Color.argb(110, 255, 210, 0);
    private static final int CHECK = Color.argb(170, 230, 30, 30);
    private static final int HINT = Color.argb(140, 40, 170, 255);
    private static final String GLYPHS = " ♟♞♝♜♛♚"; // filled pieces
    private static final String VS15 = "︎"; // force text (not emoji) rendering

    private Board board;
    private boolean flipped;
    private boolean inputEnabled = true;
    private int selected = -1;
    private final List<Integer> targets = new ArrayList<>();
    private int hintMove = 0;
    private Listener listener;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pieceFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pieceStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coord = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float cell;

    public ChessView(Context c) {
        super(c);
        pieceFill.setTextAlign(Paint.Align.CENTER);
        pieceFill.setTypeface(Typeface.DEFAULT);
        pieceStroke.setTextAlign(Paint.Align.CENTER);
        pieceStroke.setStyle(Paint.Style.STROKE);
        pieceStroke.setStrokeJoin(Paint.Join.ROUND);
        coord.setTypeface(Typeface.DEFAULT_BOLD);
    }

    public void setBoard(Board b) { board = b; clearSelection(); }
    public void setListener(Listener l) { listener = l; }
    public void setFlipped(boolean f) { flipped = f; invalidate(); }
    public boolean isFlipped() { return flipped; }
    public void setInputEnabled(boolean e) { inputEnabled = e; if (!e) clearSelection(); }
    public void setHint(int m) { hintMove = m; invalidate(); }

    public void clearSelection() { selected = -1; targets.clear(); hintMove = 0; invalidate(); }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        int size = MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED ? w : Math.min(w, h);
        setMeasuredDimension(size, size);
    }

    private int squareAt(int col, int row) { // col/row in screen space, row 0 = top
        int file = flipped ? 7 - col : col;
        int rank = flipped ? row : 7 - row;
        return rank * 8 + file;
    }

    private float sx(int s) { int f = s & 7; return (flipped ? 7 - f : f) * cell; }
    private float sy(int s) { int r = s >> 3; return (flipped ? r : 7 - r) * cell; }

    @Override
    protected void onDraw(Canvas c) {
        if (board == null) return;
        cell = getWidth() / 8f;
        int last = board.lastMove();
        int checkSq = -1;
        if (board.inCheck()) checkSq = board.king[board.side == Board.WHITE ? 0 : 1];

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                int s = squareAt(col, row);
                float x = col * cell, y = row * cell;
                fill.setColor(((s >> 3) + (s & 7)) % 2 == 0 ? DARK : LIGHT);
                c.drawRect(x, y, x + cell, y + cell, fill);
                if (last != 0 && (s == Board.from(last) || s == Board.to(last))) {
                    fill.setColor(LAST); c.drawRect(x, y, x + cell, y + cell, fill);
                }
                if (hintMove != 0 && (s == Board.from(hintMove) || s == Board.to(hintMove))) {
                    fill.setColor(HINT); c.drawRect(x, y, x + cell, y + cell, fill);
                }
                if (s == selected) { fill.setColor(SEL); c.drawRect(x, y, x + cell, y + cell, fill); }
                if (s == checkSq) {
                    fill.setColor(CHECK);
                    c.drawCircle(x + cell / 2, y + cell / 2, cell * 0.48f, fill);
                }
            }
        }

        // coordinates
        coord.setTextSize(cell * 0.2f);
        for (int i = 0; i < 8; i++) {
            int fileSq = squareAt(i, 7), rankSq = squareAt(0, i);
            coord.setColor(((fileSq >> 3) + (fileSq & 7)) % 2 == 0 ? LIGHT : DARK);
            c.drawText(String.valueOf((char) ('a' + (fileSq & 7))), i * cell + cell - cell * 0.18f, 8 * cell - cell * 0.06f, coord);
            coord.setColor(((rankSq >> 3) + (rankSq & 7)) % 2 == 0 ? LIGHT : DARK);
            c.drawText(String.valueOf((char) ('1' + (rankSq >> 3))), cell * 0.05f, i * cell + cell * 0.22f, coord);
        }

        // pieces
        pieceFill.setTextSize(cell * 0.82f);
        pieceStroke.setTextSize(cell * 0.82f);
        pieceStroke.setStrokeWidth(cell * 0.035f);
        Paint.FontMetrics fm = pieceFill.getFontMetrics();
        float yOff = -(fm.ascent + fm.descent) / 2f;
        for (int s = 0; s < 64; s++) {
            int p = board.sq[s];
            if (p == 0) continue;
            String g = GLYPHS.charAt(Math.abs(p)) + VS15;
            float cx = sx(s) + cell / 2, cy = sy(s) + cell / 2 + yOff;
            if (p > 0) {
                pieceStroke.setColor(Color.rgb(40, 40, 40));
                c.drawText(g, cx, cy, pieceStroke);
                pieceFill.setColor(Color.WHITE);
                c.drawText(g, cx, cy, pieceFill);
            } else {
                pieceStroke.setColor(Color.rgb(230, 230, 230));
                pieceStroke.setStrokeWidth(cell * 0.02f);
                c.drawText(g, cx, cy, pieceStroke);
                pieceStroke.setStrokeWidth(cell * 0.035f);
                pieceFill.setColor(Color.rgb(25, 25, 25));
                c.drawText(g, cx, cy, pieceFill);
            }
        }

        // legal move dots
        for (int t : targets) {
            float cx = sx(t) + cell / 2, cy = sy(t) + cell / 2;
            if (board.sq[t] != 0) {
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(cell * 0.08f);
                fill.setColor(Color.argb(120, 0, 0, 0));
                c.drawCircle(cx, cy, cell * 0.44f, fill);
                fill.setStyle(Paint.Style.FILL);
            } else {
                fill.setColor(Color.argb(110, 0, 0, 0));
                c.drawCircle(cx, cy, cell * 0.15f, fill);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_DOWN) return true;
        if (!inputEnabled || board == null) return true;
        int col = (int) (e.getX() / cell), row = (int) (e.getY() / cell);
        if (col < 0 || col > 7 || row < 0 || row > 7) return true;
        int s = squareAt(col, row);
        int p = board.sq[s];
        boolean own = p != 0 && (p > 0) == (board.side == Board.WHITE);

        if (selected >= 0 && targets.contains(s)) {
            int[] lm = new int[256];
            int n = board.legalMoves(lm);
            List<Integer> cands = new ArrayList<>();
            for (int i = 0; i < n; i++) if (Board.from(lm[i]) == selected && Board.to(lm[i]) == s) cands.add(lm[i]);
            clearSelection();
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (listener != null && !cands.isEmpty()) listener.onUserMove(cands);
            return true;
        }
        if (own && s != selected) {
            selected = s;
            targets.clear();
            hintMove = 0;
            int[] lm = new int[256];
            int n = board.legalMoves(lm);
            for (int i = 0; i < n; i++) if (Board.from(lm[i]) == s && !targets.contains(Board.to(lm[i]))) targets.add(Board.to(lm[i]));
        } else {
            selected = -1;
            targets.clear();
        }
        invalidate();
        return true;
    }
}
