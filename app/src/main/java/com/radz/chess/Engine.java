package com.radz.chess;

import java.util.Random;

/** Alpha-beta chess AI with quiescence search, iterative deepening and piece-square tables. */
public final class Engine {
    static final int INF = 1_000_000, MATE = 100_000;
    static final int[] VAL = {0, 100, 320, 330, 500, 900, 0};

    // Piece-square tables from white's view, index 0 = a8 ... 63 = h1 (readable layout)
    static final int[] PST_P = {
            0, 0, 0, 0, 0, 0, 0, 0,
            50, 50, 50, 50, 50, 50, 50, 50,
            10, 10, 20, 30, 30, 20, 10, 10,
            5, 5, 10, 25, 25, 10, 5, 5,
            0, 0, 0, 20, 20, 0, 0, 0,
            5, -5, -10, 0, 0, -10, -5, 5,
            5, 10, 10, -20, -20, 10, 10, 5,
            0, 0, 0, 0, 0, 0, 0, 0};
    static final int[] PST_N = {
            -50, -40, -30, -30, -30, -30, -40, -50,
            -40, -20, 0, 0, 0, 0, -20, -40,
            -30, 0, 10, 15, 15, 10, 0, -30,
            -30, 5, 15, 20, 20, 15, 5, -30,
            -30, 0, 15, 20, 20, 15, 0, -30,
            -30, 5, 10, 15, 15, 10, 5, -30,
            -40, -20, 0, 5, 5, 0, -20, -40,
            -50, -40, -30, -30, -30, -30, -40, -50};
    static final int[] PST_B = {
            -20, -10, -10, -10, -10, -10, -10, -20,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 10, 10, 5, 0, -10,
            -10, 5, 5, 10, 10, 5, 5, -10,
            -10, 0, 10, 10, 10, 10, 0, -10,
            -10, 10, 10, 10, 10, 10, 10, -10,
            -10, 5, 0, 0, 0, 0, 5, -10,
            -20, -10, -10, -10, -10, -10, -10, -20};
    static final int[] PST_R = {
            0, 0, 0, 0, 0, 0, 0, 0,
            5, 10, 10, 10, 10, 10, 10, 5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            0, 0, 0, 5, 5, 0, 0, 0};
    static final int[] PST_Q = {
            -20, -10, -10, -5, -5, -10, -10, -20,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 5, 5, 5, 0, -10,
            -5, 0, 5, 5, 5, 5, 0, -5,
            0, 0, 5, 5, 5, 5, 0, -5,
            -10, 5, 5, 5, 5, 5, 0, -10,
            -10, 0, 5, 0, 0, 0, 0, -10,
            -20, -10, -10, -5, -5, -10, -10, -20};
    static final int[] PST_K_MID = {
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -20, -30, -30, -40, -40, -30, -30, -20,
            -10, -20, -20, -20, -20, -20, -20, -10,
            20, 20, 0, 0, 0, 0, 20, 20,
            20, 30, 10, 0, 0, 10, 30, 20};
    static final int[] PST_K_END = {
            -50, -40, -30, -20, -20, -30, -40, -50,
            -30, -20, -10, 0, 0, -10, -20, -30,
            -30, -10, 20, 30, 30, 20, -10, -30,
            -30, -10, 30, 40, 40, 30, -10, -30,
            -30, -10, 30, 40, 40, 30, -10, -30,
            -30, -10, 20, 30, 30, 20, -10, -30,
            -30, -30, 0, 0, 0, 0, -30, -30,
            -50, -30, -30, -30, -30, -30, -30, -50};
    static final int[][] PST = {null, PST_P, PST_N, PST_B, PST_R, PST_Q, PST_K_MID};

    public static final String[] LEVEL_NAMES = {"Beginner", "Easy", "Medium", "Hard", "Expert"};

    private final Random rnd = new Random();
    private Board b;
    private long deadline;
    private boolean stopped;
    private long nodes;
    private final int[][] killers = new int[64][2];
    private final int[][] moveBuf = new int[64][256];
    private final int[][] scoreBuf = new int[64][256];
    private volatile boolean abort;

    public void abort() { abort = true; }

    /** Static evaluation from the side-to-move's perspective. */
    int evaluate() {
        int mg = 0, nonPawn = 0;
        int wb = 0, bb = 0;
        for (int i = 0; i < 64; i++) {
            int p = b.sq[i];
            if (p == 0) continue;
            int t = Math.abs(p);
            if (t != Board.PAWN && t != Board.KING) nonPawn += VAL[t];
            if (t == Board.BISHOP) { if (p > 0) wb++; else bb++; }
        }
        boolean endgame = nonPawn <= 1300;
        for (int i = 0; i < 64; i++) {
            int p = b.sq[i];
            if (p == 0) continue;
            int t = Math.abs(p);
            int idx = p > 0 ? (7 - (i >> 3)) * 8 + (i & 7) : i; // map to table layout
            int[] tab = (t == Board.KING && endgame) ? PST_K_END : PST[t];
            int v = VAL[t] + tab[idx];
            mg += p > 0 ? v : -v;
        }
        if (wb >= 2) mg += 30;
        if (bb >= 2) mg -= 30;
        // Endgame: push losing king to the edge and bring kings together, helps finish mates
        if (endgame) {
            int wk = b.king[0], bk = b.king[1];
            int dist = Math.abs((wk >> 3) - (bk >> 3)) + Math.abs((wk & 7) - (bk & 7));
            if (mg > 200) mg += centreDist(bk) * 10 + (14 - dist) * 4;
            else if (mg < -200) mg -= centreDist(wk) * 10 + (14 - dist) * 4;
        }
        return mg * b.side;
    }

    private static int centreDist(int s) {
        int r = s >> 3, f = s & 7;
        return Math.max(3 - r, r - 4) + Math.max(3 - f, f - 4);
    }

    private void scoreMoves(int[] moves, int[] scores, int n, int ply, int best) {
        for (int i = 0; i < n; i++) {
            int m = moves[i];
            int s;
            if (m == best) s = 10_000_000;
            else {
                int victim = Math.abs(b.sq[Board.to(m)]);
                if (Board.flag(m) == Board.F_EP) victim = Board.PAWN;
                if (victim != 0) s = 1_000_000 + VAL[victim] * 10 - VAL[Math.abs(b.sq[Board.from(m)])] / 10;
                else if (Board.promo(m) != 0) s = 900_000 + Board.promo(m);
                else if (ply < 64 && m == killers[ply][0]) s = 800_000;
                else if (ply < 64 && m == killers[ply][1]) s = 700_000;
                else s = 0;
            }
            scores[i] = s;
        }
    }

    private static void pickBest(int[] moves, int[] scores, int n, int start) {
        int bi = start;
        for (int i = start + 1; i < n; i++) if (scores[i] > scores[bi]) bi = i;
        if (bi != start) {
            int t = moves[start]; moves[start] = moves[bi]; moves[bi] = t;
            t = scores[start]; scores[start] = scores[bi]; scores[bi] = t;
        }
    }

    private boolean timeUp() {
        if ((++nodes & 1023) == 0 && (System.currentTimeMillis() > deadline || abort)) stopped = true;
        return stopped;
    }

    private int quiesce(int alpha, int beta, int ply) {
        if (timeUp()) return 0;
        int stand = evaluate();
        if (stand >= beta) return beta;
        if (stand > alpha) alpha = stand;
        if (ply >= 63) return stand;
        int[] moves = moveBuf[ply], scores = scoreBuf[ply];
        int n = b.genMoves(moves, true);
        scoreMoves(moves, scores, n, 64, 0);
        for (int i = 0; i < n; i++) {
            pickBest(moves, scores, n, i);
            if (!b.makeMove(moves[i])) continue;
            int sc = -quiesce(-beta, -alpha, ply + 1);
            b.unmakeMove();
            if (stopped) return 0;
            if (sc >= beta) return beta;
            if (sc > alpha) alpha = sc;
        }
        return alpha;
    }

    private int search(int depth, int alpha, int beta, int ply) {
        if (ply > 0) {
            if (b.halfmove >= 100 || b.repetitions() >= 2 || b.insufficientMaterial()) return 0;
        }
        boolean check = b.inCheck();
        if (check) depth++; // check extension
        if (depth <= 0) return quiesce(alpha, beta, ply);
        if (timeUp()) return 0;
        if (ply >= 62) return evaluate();

        int[] moves = moveBuf[ply], scores = scoreBuf[ply];
        int n = b.genMoves(moves, false);
        scoreMoves(moves, scores, n, ply, 0);
        int legal = 0, best = -INF;
        for (int i = 0; i < n; i++) {
            pickBest(moves, scores, n, i);
            int m = moves[i];
            boolean quiet = b.sq[Board.to(m)] == 0 && Board.flag(m) != Board.F_EP && Board.promo(m) == 0;
            if (!b.makeMove(m)) continue;
            legal++;
            int sc = -search(depth - 1, -beta, -alpha, ply + 1);
            b.unmakeMove();
            if (stopped) return 0;
            if (sc > best) best = sc;
            if (sc > alpha) alpha = sc;
            if (alpha >= beta) {
                if (quiet && killers[ply][0] != m) { killers[ply][1] = killers[ply][0]; killers[ply][0] = m; }
                break;
            }
        }
        if (legal == 0) return check ? -MATE + ply : 0;
        return best;
    }

    /** Root search at fixed depth, returning per-move scores (used for the weaker, noisier levels). */
    private int searchRootNoisy(int[] root, int n, int depth, int noise) {
        int bestMove = root[0], bestScore = -INF;
        for (int i = 0; i < n; i++) {
            b.makeMove(root[i]);
            int sc = -search(depth - 1, -INF, INF, 1);
            b.unmakeMove();
            if (stopped) break;
            int noisy = sc + (noise > 0 ? rnd.nextInt(noise * 2 + 1) - noise : 0);
            if (noisy > bestScore) { bestScore = noisy; bestMove = root[i]; }
        }
        return bestMove;
    }

    /** Pick a move for the side to move. level 0..4. Returns 0 if no legal moves. */
    public int findMove(Board board, int level) {
        // Work on a private copy so the UI board is never touched from this thread
        b = new Board();
        copy(board, b);
        abort = false;
        stopped = false;
        nodes = 0;
        for (int[] k : killers) { k[0] = 0; k[1] = 0; }

        int[] root = new int[256];
        int n = b.legalMoves(root);
        if (n == 0) return 0;
        if (n == 1) return root[0];

        int[] depths = {1, 2, 3, 5, 64};
        long[] times = {400, 800, 1500, 2500, 5000};
        int[] noise = {250, 90, 25, 0, 0};
        deadline = System.currentTimeMillis() + times[level];

        // Shuffle so equal moves vary between games
        for (int i = n - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = root[i]; root[i] = root[j]; root[j] = t;
        }

        if (noise[level] > 0) {
            int d = depths[level];
            int best = searchRootNoisy(root, n, d, noise[level]);
            return best;
        }

        // Light opening variety for the strong levels
        if (board.ply < 6) {
            int best = searchRootNoisy(root, n, 3, 15);
            if (!stopped) return best;
            stopped = false;
            deadline = System.currentTimeMillis() + times[level];
        }

        int bestMove = root[0];
        int[] scores = new int[256];
        for (int depth = 1; depth <= depths[level]; depth++) {
            scoreMoves(root, scores, n, 0, bestMove);
            int alpha = -INF, iterBest = 0, iterScore = -INF;
            for (int i = 0; i < n; i++) {
                pickBest(root, scores, n, i);
                b.makeMove(root[i]);
                int sc = -search(depth - 1, -INF, -alpha, 1);
                b.unmakeMove();
                if (stopped) break;
                if (sc > iterScore) { iterScore = sc; iterBest = root[i]; }
                if (sc > alpha) alpha = sc;
            }
            if (stopped) {
                if (iterBest != 0 && iterScore > -INF) bestMove = iterBest; // partial result still useful (best move searched first)
                break;
            }
            bestMove = iterBest;
            if (iterScore > MATE - 100) break; // found a forced mate
            if (System.currentTimeMillis() > deadline - (times[level] / 2)) break; // next depth likely won't finish
        }
        return bestMove;
    }

    /** Copies position + history (for repetition detection) by replaying moves. */
    static void copy(Board src, Board dst) {
        // Rebuild by replaying the move list from the starting position stored in src
        dst.loadFen(src.startFen);
        for (int i = 0; i < src.ply; i++) dst.makeMove(src.moveAt(i));
    }
}
