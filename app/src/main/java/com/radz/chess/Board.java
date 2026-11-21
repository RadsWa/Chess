package com.radz.chess;

import java.util.Random;

/**
 * Chess board and rules. Squares 0..63, index = rank * 8 + file, a1 = 0, h8 = 63.
 * Pieces: positive = white, negative = black. 1 pawn, 2 knight, 3 bishop, 4 rook, 5 queen, 6 king.
 * Moves are packed ints: from | to<<6 | promo<<12 | flag<<15.
 */
public final class Board {
    public static final int EMPTY = 0, PAWN = 1, KNIGHT = 2, BISHOP = 3, ROOK = 4, QUEEN = 5, KING = 6;
    public static final int WHITE = 1, BLACK = -1;
    public static final int F_NONE = 0, F_DOUBLE = 1, F_EP = 2, F_CASTLE = 3;
    static final int WK_C = 1, WQ_C = 2, BK_C = 4, BQ_C = 8;

    public final int[] sq = new int[64];
    public int side = WHITE;
    public int castling = 15;
    public int ep = -1;
    public int halfmove = 0;
    public int fullmove = 1;
    public long hash;
    public String startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    final int[] king = new int[2]; // [0]=white, [1]=black

    // Undo stack
    private static final int MAX = 2048;
    private final int[] uMove = new int[MAX], uCap = new int[MAX], uCastle = new int[MAX],
            uEp = new int[MAX], uHalf = new int[MAX];
    private final long[] uHash = new long[MAX];
    public int ply = 0; // number of moves on the stack

    // Zobrist keys
    private static final long[][] Z_PIECE = new long[13][64];
    private static final long Z_SIDE;
    private static final long[] Z_CASTLE = new long[16];
    private static final long[] Z_EP = new long[8];

    static {
        Random r = new Random(20261121L);
        for (int p = 0; p < 13; p++) for (int s = 0; s < 64; s++) Z_PIECE[p][s] = r.nextLong();
        Z_SIDE = r.nextLong();
        for (int i = 0; i < 16; i++) Z_CASTLE[i] = r.nextLong();
        for (int i = 0; i < 8; i++) Z_EP[i] = r.nextLong();
    }

    private static final int[] KN = {-17, -15, -10, -6, 6, 10, 15, 17};
    private static final int[][] KN_D = {{-2, -1}, {-2, 1}, {-1, -2}, {-1, 2}, {1, -2}, {1, 2}, {2, -1}, {2, 1}};
    private static final int[][] KING_D = {{-1, -1}, {-1, 0}, {-1, 1}, {0, -1}, {0, 1}, {1, -1}, {1, 0}, {1, 1}};
    private static final int[][] DIAG = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};
    private static final int[][] ORTH = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    public Board() { reset(); }

    public static int from(int m) { return m & 63; }
    public static int to(int m) { return (m >> 6) & 63; }
    public static int promo(int m) { return (m >> 12) & 7; }
    public static int flag(int m) { return (m >> 15) & 7; }
    public static int mk(int f, int t, int p, int fl) { return f | (t << 6) | (p << 12) | (fl << 15); }

    private static int zi(int piece) { return piece + 6; }

    public void reset() { loadFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"); }

    public void loadFen(String fen) {
        startFen = fen.trim();
        String[] parts = fen.trim().split("\\s+");
        for (int i = 0; i < 64; i++) sq[i] = EMPTY;
        int r = 7, f = 0;
        for (char c : parts[0].toCharArray()) {
            if (c == '/') { r--; f = 0; }
            else if (Character.isDigit(c)) f += c - '0';
            else {
                int t = "pnbrqk".indexOf(Character.toLowerCase(c)) + 1;
                sq[r * 8 + f] = Character.isUpperCase(c) ? t : -t;
                f++;
            }
        }
        side = parts.length > 1 && parts[1].equals("b") ? BLACK : WHITE;
        castling = 0;
        if (parts.length > 2) {
            if (parts[2].contains("K")) castling |= WK_C;
            if (parts[2].contains("Q")) castling |= WQ_C;
            if (parts[2].contains("k")) castling |= BK_C;
            if (parts[2].contains("q")) castling |= BQ_C;
        }
        ep = -1;
        if (parts.length > 3 && !parts[3].equals("-")) ep = parseSquare(parts[3]);
        halfmove = parts.length > 4 ? Integer.parseInt(parts[4]) : 0;
        fullmove = parts.length > 5 ? Integer.parseInt(parts[5]) : 1;
        ply = 0;
        for (int i = 0; i < 64; i++) {
            if (sq[i] == KING) king[0] = i;
            if (sq[i] == -KING) king[1] = i;
        }
        hash = computeHash();
    }

    public long computeHash() {
        long h = 0;
        for (int i = 0; i < 64; i++) if (sq[i] != EMPTY) h ^= Z_PIECE[zi(sq[i])][i];
        if (side == BLACK) h ^= Z_SIDE;
        h ^= Z_CASTLE[castling];
        if (ep >= 0) h ^= Z_EP[ep & 7];
        return h;
    }

    public static int parseSquare(String s) { return (s.charAt(1) - '1') * 8 + (s.charAt(0) - 'a'); }
    public static String squareName(int s) { return "" + (char) ('a' + (s & 7)) + (char) ('1' + (s >> 3)); }

    public String moveToUci(int m) {
        String s = squareName(from(m)) + squareName(to(m));
        if (promo(m) != 0) s += " nbrq".charAt(promo(m) - 1);
        return s;
    }

    // ---------- attack detection ----------
    public boolean isAttacked(int s, int by) {
        int r = s >> 3, f = s & 7;
        // pawns
        int pr = r - by; // attacking pawn sits one rank "behind" from its own perspective
        if (pr >= 0 && pr < 8) {
            if (f > 0 && sq[pr * 8 + f - 1] == PAWN * by) return true;
            if (f < 7 && sq[pr * 8 + f + 1] == PAWN * by) return true;
        }
        for (int[] d : KN_D) {
            int rr = r + d[0], ff = f + d[1];
            if (rr >= 0 && rr < 8 && ff >= 0 && ff < 8 && sq[rr * 8 + ff] == KNIGHT * by) return true;
        }
        for (int[] d : KING_D) {
            int rr = r + d[0], ff = f + d[1];
            if (rr >= 0 && rr < 8 && ff >= 0 && ff < 8 && sq[rr * 8 + ff] == KING * by) return true;
        }
        for (int[] d : DIAG) {
            int rr = r + d[0], ff = f + d[1];
            while (rr >= 0 && rr < 8 && ff >= 0 && ff < 8) {
                int p = sq[rr * 8 + ff];
                if (p != EMPTY) { if (p == BISHOP * by || p == QUEEN * by) return true; break; }
                rr += d[0]; ff += d[1];
            }
        }
        for (int[] d : ORTH) {
            int rr = r + d[0], ff = f + d[1];
            while (rr >= 0 && rr < 8 && ff >= 0 && ff < 8) {
                int p = sq[rr * 8 + ff];
                if (p != EMPTY) { if (p == ROOK * by || p == QUEEN * by) return true; break; }
                rr += d[0]; ff += d[1];
            }
        }
        return false;
    }

    public boolean inCheck(int who) { return isAttacked(king[who == WHITE ? 0 : 1], -who); }
    public boolean inCheck() { return inCheck(side); }

    // ---------- move generation ----------
    /** Pseudo-legal moves into list, returns count. capturesOnly for quiescence. */
    public int genMoves(int[] list, boolean capturesOnly) {
        int n = 0;
        int us = side;
        for (int s = 0; s < 64; s++) {
            int p = sq[s];
            if (p == EMPTY || (p > 0) != (us == WHITE)) continue;
            int t = Math.abs(p), r = s >> 3, f = s & 7;
            switch (t) {
                case PAWN: {
                    int dir = us == WHITE ? 8 : -8;
                    int startR = us == WHITE ? 1 : 6, lastR = us == WHITE ? 7 : 0;
                    int one = s + dir;
                    if (one >= 0 && one < 64 && sq[one] == EMPTY) {
                        if ((one >> 3) == lastR) n = addPromos(list, n, s, one);
                        else if (!capturesOnly) {
                            list[n++] = mk(s, one, 0, F_NONE);
                            int two = one + dir;
                            if (r == startR && sq[two] == EMPTY) list[n++] = mk(s, two, 0, F_DOUBLE);
                        }
                    }
                    for (int df = -1; df <= 1; df += 2) {
                        int ff = f + df;
                        if (ff < 0 || ff > 7) continue;
                        int c = one + df;
                        if (c < 0 || c > 63) continue;
                        int cp = sq[c];
                        if (cp != EMPTY && (cp > 0) != (us == WHITE)) {
                            if ((c >> 3) == lastR) n = addPromos(list, n, s, c);
                            else list[n++] = mk(s, c, 0, F_NONE);
                        } else if (c == ep) {
                            list[n++] = mk(s, c, 0, F_EP);
                        }
                    }
                    break;
                }
                case KNIGHT:
                    n = stepMoves(list, n, s, r, f, KN_D, capturesOnly);
                    break;
                case BISHOP:
                    n = slideMoves(list, n, s, r, f, DIAG, capturesOnly);
                    break;
                case ROOK:
                    n = slideMoves(list, n, s, r, f, ORTH, capturesOnly);
                    break;
                case QUEEN:
                    n = slideMoves(list, n, s, r, f, DIAG, capturesOnly);
                    n = slideMoves(list, n, s, r, f, ORTH, capturesOnly);
                    break;
                case KING:
                    n = stepMoves(list, n, s, r, f, KING_D, capturesOnly);
                    if (!capturesOnly) n = castleMoves(list, n, us);
                    break;
            }
        }
        return n;
    }

    private int addPromos(int[] list, int n, int f, int t) {
        list[n++] = mk(f, t, QUEEN, F_NONE);
        list[n++] = mk(f, t, KNIGHT, F_NONE);
        list[n++] = mk(f, t, ROOK, F_NONE);
        list[n++] = mk(f, t, BISHOP, F_NONE);
        return n;
    }

    private int stepMoves(int[] list, int n, int s, int r, int f, int[][] dirs, boolean capOnly) {
        for (int[] d : dirs) {
            int rr = r + d[0], ff = f + d[1];
            if (rr < 0 || rr > 7 || ff < 0 || ff > 7) continue;
            int t = rr * 8 + ff, p = sq[t];
            if (p == EMPTY) { if (!capOnly) list[n++] = mk(s, t, 0, F_NONE); }
            else if ((p > 0) != (side == WHITE)) list[n++] = mk(s, t, 0, F_NONE);
        }
        return n;
    }

    private int slideMoves(int[] list, int n, int s, int r, int f, int[][] dirs, boolean capOnly) {
        for (int[] d : dirs) {
            int rr = r + d[0], ff = f + d[1];
            while (rr >= 0 && rr < 8 && ff >= 0 && ff < 8) {
                int t = rr * 8 + ff, p = sq[t];
                if (p == EMPTY) { if (!capOnly) list[n++] = mk(s, t, 0, F_NONE); }
                else { if ((p > 0) != (side == WHITE)) list[n++] = mk(s, t, 0, F_NONE); break; }
                rr += d[0]; ff += d[1];
            }
        }
        return n;
    }

    private int castleMoves(int[] list, int n, int us) {
        if (us == WHITE) {
            if ((castling & WK_C) != 0 && sq[5] == EMPTY && sq[6] == EMPTY && sq[7] == ROOK
                    && !isAttacked(4, BLACK) && !isAttacked(5, BLACK) && !isAttacked(6, BLACK))
                list[n++] = mk(4, 6, 0, F_CASTLE);
            if ((castling & WQ_C) != 0 && sq[3] == EMPTY && sq[2] == EMPTY && sq[1] == EMPTY && sq[0] == ROOK
                    && !isAttacked(4, BLACK) && !isAttacked(3, BLACK) && !isAttacked(2, BLACK))
                list[n++] = mk(4, 2, 0, F_CASTLE);
        } else {
            if ((castling & BK_C) != 0 && sq[61] == EMPTY && sq[62] == EMPTY && sq[63] == -ROOK
                    && !isAttacked(60, WHITE) && !isAttacked(61, WHITE) && !isAttacked(62, WHITE))
                list[n++] = mk(60, 62, 0, F_CASTLE);
            if ((castling & BQ_C) != 0 && sq[59] == EMPTY && sq[58] == EMPTY && sq[57] == EMPTY && sq[56] == -ROOK
                    && !isAttacked(60, WHITE) && !isAttacked(59, WHITE) && !isAttacked(58, WHITE))
                list[n++] = mk(60, 58, 0, F_CASTLE);
        }
        return n;
    }

    // Castling rights cleared when these squares are touched
    private static final int[] CASTLE_MASK = new int[64];
    static {
        for (int i = 0; i < 64; i++) CASTLE_MASK[i] = 15;
        CASTLE_MASK[0] = 15 & ~WQ_C; CASTLE_MASK[7] = 15 & ~WK_C; CASTLE_MASK[4] = 15 & ~(WK_C | WQ_C);
        CASTLE_MASK[56] = 15 & ~BQ_C; CASTLE_MASK[63] = 15 & ~BK_C; CASTLE_MASK[60] = 15 & ~(BK_C | BQ_C);
    }

    /** Makes a pseudo-legal move. Returns false (and undoes it) if it leaves own king in check. */
    public boolean makeMove(int m) {
        int f = from(m), t = to(m), fl = flag(m), pr = promo(m);
        int p = sq[f], us = side;
        int cap = sq[t];
        uMove[ply] = m; uCastle[ply] = castling; uEp[ply] = ep; uHalf[ply] = halfmove; uHash[ply] = hash;

        long h = hash;
        if (ep >= 0) h ^= Z_EP[ep & 7];
        h ^= Z_CASTLE[castling];

        if (fl == F_EP) {
            int cs = t - 8 * us;
            cap = sq[cs];
            sq[cs] = EMPTY;
            h ^= Z_PIECE[zi(cap)][cs];
        } else if (cap != EMPTY) {
            h ^= Z_PIECE[zi(cap)][t];
        }
        uCap[ply] = cap;

        sq[f] = EMPTY;
        h ^= Z_PIECE[zi(p)][f];
        int placed = pr != 0 ? pr * us : p;
        sq[t] = placed;
        h ^= Z_PIECE[zi(placed)][t];

        if (fl == F_CASTLE) {
            int rf, rt;
            if (t == f + 2) { rf = f + 3; rt = f + 1; } else { rf = f - 4; rt = f - 1; }
            int rook = sq[rf];
            sq[rf] = EMPTY; sq[rt] = rook;
            h ^= Z_PIECE[zi(rook)][rf] ^ Z_PIECE[zi(rook)][rt];
        }
        if (Math.abs(p) == KING) king[us == WHITE ? 0 : 1] = t;

        castling &= CASTLE_MASK[f] & CASTLE_MASK[t];
        h ^= Z_CASTLE[castling];
        ep = fl == F_DOUBLE ? (f + t) / 2 : -1;
        if (ep >= 0) h ^= Z_EP[ep & 7];
        halfmove = (Math.abs(p) == PAWN || cap != EMPTY) ? 0 : halfmove + 1;
        if (us == BLACK) fullmove++;
        side = -us;
        h ^= Z_SIDE;
        hash = h;
        ply++;
        if (inCheck(us)) { unmakeMove(); return false; }
        return true;
    }

    public void unmakeMove() {
        ply--;
        int m = uMove[ply];
        int f = from(m), t = to(m), fl = flag(m), pr = promo(m);
        side = -side;
        int us = side;
        int p = sq[t];
        if (pr != 0) p = PAWN * us;
        sq[f] = p;
        sq[t] = EMPTY;
        int cap = uCap[ply];
        if (fl == F_EP) sq[t - 8 * us] = cap;
        else sq[t] = cap;
        if (fl == F_CASTLE) {
            int rf, rt;
            if (t == f + 2) { rf = f + 3; rt = f + 1; } else { rf = f - 4; rt = f - 1; }
            sq[rf] = sq[rt]; sq[rt] = EMPTY;
        }
        if (Math.abs(p) == KING) king[us == WHITE ? 0 : 1] = f;
        castling = uCastle[ply]; ep = uEp[ply]; halfmove = uHalf[ply]; hash = uHash[ply];
        if (us == BLACK) fullmove--;
    }

    public int lastMove() { return ply > 0 ? uMove[ply - 1] : 0; }
    public int capturedAt(int i) { return uCap[i]; }
    public int moveAt(int i) { return uMove[i]; }

    /** Fully legal moves. */
    public int legalMoves(int[] out) {
        int[] tmp = new int[256];
        int n = genMoves(tmp, false), k = 0;
        for (int i = 0; i < n; i++) {
            if (makeMove(tmp[i])) { unmakeMove(); out[k++] = tmp[i]; }
        }
        return k;
    }

    /** Number of times the current position has occurred (within reversible moves). */
    public int repetitions() {
        int count = 1;
        int limit = Math.max(0, ply - halfmove);
        for (int i = ply - 2; i >= limit; i -= 2) if (uHash[i] == hash) count++;
        return count;
    }

    public boolean insufficientMaterial() {
        int minors = 0, bishopsColour = -1;
        boolean mixedBishops = false;
        for (int i = 0; i < 64; i++) {
            int t = Math.abs(sq[i]);
            if (t == PAWN || t == ROOK || t == QUEEN) return false;
            if (t == KNIGHT) minors++;
            if (t == BISHOP) {
                minors++;
                int c = ((i >> 3) + (i & 7)) & 1;
                if (bishopsColour == -1) bishopsColour = c; else if (bishopsColour != c) mixedBishops = true;
            }
        }
        if (minors <= 1) return true;
        // Only bishops, all on the same colour
        boolean anyKnight = false;
        for (int i = 0; i < 64; i++) if (Math.abs(sq[i]) == KNIGHT) anyKnight = true;
        return !anyKnight && !mixedBishops;
    }

    /** Standard algebraic notation; call BEFORE making the move. */
    public String toSan(int m) {
        int f = from(m), t = to(m), p = Math.abs(sq[f]);
        String s;
        if (flag(m) == F_CASTLE) s = (t > f) ? "O-O" : "O-O-O";
        else {
            boolean capture = sq[t] != EMPTY || flag(m) == F_EP;
            StringBuilder b = new StringBuilder();
            if (p == PAWN) {
                if (capture) b.append((char) ('a' + (f & 7)));
            } else {
                b.append(" PNBRQK".charAt(p));
                int[] lm = new int[256];
                int n = legalMoves(lm);
                boolean amb = false, sameFile = false, sameRank = false;
                for (int i = 0; i < n; i++) {
                    int o = lm[i];
                    if (o != m && to(o) == t && Math.abs(sq[from(o)]) == p) {
                        amb = true;
                        if ((from(o) & 7) == (f & 7)) sameFile = true;
                        if ((from(o) >> 3) == (f >> 3)) sameRank = true;
                    }
                }
                if (amb) {
                    if (!sameFile) b.append((char) ('a' + (f & 7)));
                    else if (!sameRank) b.append((char) ('1' + (f >> 3)));
                    else b.append(squareName(f));
                }
            }
            if (capture) b.append('x');
            b.append(squareName(t));
            if (promo(m) != 0) b.append('=').append(" PNBRQK".charAt(promo(m)));
            s = b.toString();
        }
        if (makeMove(m)) {
            if (inCheck()) {
                int[] lm = new int[256];
                s += legalMoves(lm) == 0 ? "#" : "+";
            }
            unmakeMove();
        }
        return s;
    }

    public long perft(int depth) {
        if (depth == 0) return 1;
        int[] list = new int[256];
        int n = genMoves(list, false);
        long total = 0;
        for (int i = 0; i < n; i++) {
            if (makeMove(list[i])) { total += perft(depth - 1); unmakeMove(); }
        }
        return total;
    }
}
