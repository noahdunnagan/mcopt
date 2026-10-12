// Far terrain as meshes: see lodmesh.h. Plain C (no Objective-C), compiled into the game's native library and into the
// offline harness.
#include "lodmesh.h"

#include <stdlib.h>
#include <string.h>

#define GEOM_WET 0x1000u
#define GEOM_VALID 0x2000u
#define GEOM_CROWN 0x4000u
#define GEOM_CLEAR (1u << 24)   // (a real chunk's top that doesn't hide what's behind it: no occluder)
#define GEOM_DEPTH(g) ((int) (((g) >> 25) & 127u))   // (a real chunk's: how deep its top solid run reaches; 0 all the way)
#define NEG_INF (-100000)
#define MAX_IV 5
#define REGION (LM_TILE + 2)

// A cell's solid y-intervals [lo, hi] (face heights; lo NEG_INF: the ground, down to the world's bottom), bottom up. n -1: unknown
// (no data: a neighbor tile that isn't resident).
typedef struct {
	int n;
	int lo[MAX_IV], hi[MAX_IV];
	unsigned char kind[MAX_IV];
	unsigned char clear;
	// the top is water's (GEOM_WET): hi[0] is the water's surface
	unsigned char wet;
	// a real chunk's column: its first interval's solid run reaches down to bot only (under it, maybe a cave or the air under
	// an overhang); NEG_INF: solid all the way down
	int bot;
} Col;

static void addIv(Col *c, int lo, int hi, int kind) {
	if (hi <= lo) return;
	// runs never reach below the one before them; overlaps (malformed words) are joined
	if (c->n > 0 && lo <= c->hi[c->n - 1]) {
		if (hi > c->hi[c->n - 1]) c->hi[c->n - 1] = hi;
		return;
	}
	if (c->n == MAX_IV) return;
	c->lo[c->n] = lo;
	c->hi[c->n] = hi;
	c->kind[c->n] = (unsigned char) kind;
	c->n++;
}

static void column0(const LmIn *in, int ax, int az, Col *c);

static void column(const LmIn *in, int ax, int az, Col *c) {
	column0(in, ax, az, c);
	int m = (1 << in->logN) - 1;
	int depth = GEOM_DEPTH(in->geom[((size_t) (az & m) << in->logN) | (size_t) (ax & m)]);
	c->bot = depth > 0 && c->n > 0 ? c->hi[0] - depth : NEG_INF;
}

static void column0(const LmIn *in, int ax, int az, Col *c) {
	int m = (1 << in->logN) - 1;
	size_t idx = ((size_t) (az & m) << in->logN) | (size_t) (ax & m);
	uint32_t g = in->geom[idx];
	c->n = 0;
	c->clear = (g & GEOM_CLEAR) != 0;
	c->wet = (g & GEOM_WET) != 0;
	if (!(g & GEOM_VALID)) {
		c->n = -1;
		return;
	}
	int top = (int) (g & 0xFFFu) - 512;
	if ((g & GEOM_CROWN) && in->crown) {
		uint32_t cr = in->crown[idx];
		int ground = (int) (cr & 0xFFFu) - 512;
		int thick = (int) ((g >> 15) & 63u);
		int y = top - thick;
		addIv(c, NEG_INF, ground, LM_K_UNDER);
		uint32_t runs = in->runs ? in->runs[idx] : 0;
		if (runs == 0) {
			addIv(c, y, top, LM_K_CROWN);
		} else {
			for (int k = 0; k < 4; k++) {
				uint32_t run = (runs >> (8 * k)) & 255u, len = run >> 3;
				if (len == 0) break;
				y += (int) (run & 7u);
				addIv(c, y, y + (int) len, LM_K_CROWN);
				y += (int) len;
			}
		}
		return;
	}
	addIv(c, NEG_INF, top, LM_K_GROUND);
}

// a's intervals where b has none (b unknown: all of a's, the ground's from LM_DEEP). Returns the pieces.
static int faceDiff(const Col *a, const Col *b, int *lo, int *hi, unsigned char *kind) {
	int n = 0;
	for (int k = 0; k < a->n; k++) {
		int pl[MAX_IV * 2 + 2], ph[MAX_IV * 2 + 2], pn = 1;
		pl[0] = a->lo[k];
		ph[0] = a->hi[k];
		for (int j = 0; b->n > 0 && j < b->n; j++) {
			int bl = b->lo[j], bh = b->hi[j], qn = 0;
			int ql[MAX_IV * 2 + 2], qh[MAX_IV * 2 + 2];
			for (int p = 0; p < pn; p++) {
				if (bh <= pl[p] || bl >= ph[p]) {
					ql[qn] = pl[p];
					qh[qn++] = ph[p];
					continue;
				}
				if (bl > pl[p]) {
					ql[qn] = pl[p];
					qh[qn++] = bl;
				}
				if (ph[p] > bh) {
					ql[qn] = bh;
					qh[qn++] = ph[p];
				}
			}
			pn = qn;
			memcpy(pl, ql, sizeof(int) * (size_t) qn);
			memcpy(ph, qh, sizeof(int) * (size_t) qn);
		}
		for (int p = 0; p < pn && n < MAX_IV * 2; p++) {
			int l = pl[p] < LM_DEEP ? LM_DEEP : pl[p], h = ph[p];
			if (h <= l) continue;
			lo[n] = l;
			hi[n] = h;
			kind[n++] = a->kind[k];
		}
	}
	return n;
}

typedef struct {
	uint32_t *quads;
	int count, cap;
	int overflow;
	int minY, maxY;
} Out;

static void emitf(Out *o, int x, int z, int e1, int e2, int face, int kind, int y0, int y1, int extra, int flags);

static void emit(Out *o, int x, int z, int e1, int e2, int face, int kind, int y0, int y1, int extra) {
	emitf(o, x, z, e1, e2, face, kind, y0, y1, extra, 0);
}

// flags: 1 = a skirt's ground piece (bit 31 of w0): the GPU lowers its foot to the surface across the seam, whatever level draws it
static void emitf(Out *o, int x, int z, int e1, int e2, int face, int kind, int y0, int y1, int extra, int flags) {
	if (o->count >= o->cap) {
		o->overflow = 1;
		return;
	}
	if (y0 < LM_DEEP) y0 = LM_DEEP;
	if (y1 < LM_DEEP) y1 = LM_DEEP;
	if (y0 > 3583) y0 = 3583;
	if (y1 > 3583) y1 = 3583;
	uint32_t *q = o->quads + (size_t) o->count * 2;
	q[0] = (uint32_t) x | (uint32_t) z << 7 | (uint32_t) (e1 - 1) << 14 | (uint32_t) (e2 - 1) << 20 | (uint32_t) face << 26 | (uint32_t) kind << 29
		| (uint32_t) (flags & 1) << 31;
	q[1] = (uint32_t) (y0 + 512) | (uint32_t) (y1 + 512) << 12 | (uint32_t) (extra & 255) << 24;
	o->count++;
}

static void range(Out *o, int lo, int hi) {
	if (lo < o->minY) o->minY = lo;
	if (hi > o->maxY) o->maxY = hi;
}

// Greedy rectangles over one layer of a block: rows[z] has bit x set where the cell has the face.
static void rects(Out *o, uint32_t *rows, int b, int x0, int z0, int face, int kind, int y) {
	for (int z = 0; z < b; z++) {
		while (rows[z]) {
			int x = __builtin_ctz(rows[z]);
			int w = 0;
			while (x + w < b && (rows[z] >> (x + w) & 1u)) w++;
			uint32_t m = (w >= 32 ? 0xFFFFFFFFu : ((1u << w) - 1u)) << x;
			int h = 1;
			while (z + h < b && (rows[z + h] & m) == m) h++;
			for (int k = 0; k < h; k++) rows[z + k] &= ~m;
			emit(o, x0 + x, z0 + z, w, h, face, kind, y, y, 0);
		}
	}
}

typedef struct {
	int key;   // kind << 16 | (y + 512)
	int x, z;
} Item;

static int itemCmp(const void *a, const void *b) {
	const Item *p = a, *q = b;
	return p->key != q->key ? (p->key < q->key ? -1 : 1) : 0;
}

// Tops (bottoms = 0) or undersides (bottoms = 1) of the block's cells, merged per height and kind.
static void flats(Out *o, const Col *cols, int b, int x0, int z0, int bottoms) {
	Item items[16 * 16 * MAX_IV];
	int n = 0;
	for (int z = 0; z < b; z++) {
		for (int x = 0; x < b; x++) {
			const Col *c = &cols[(z0 + z + 1) * REGION + (x0 + x + 1)];
			for (int k = 0; k < c->n; k++) {
				if (bottoms && c->lo[k] == NEG_INF) continue;
				int y = bottoms ? c->lo[k] : c->hi[k];
				items[n].key = (int) c->kind[k] << 16 | (y + 512);
				items[n].x = x;
				items[n].z = z;
				n++;
			}
		}
	}
	if (n == 0) return;
	qsort(items, (size_t) n, sizeof(Item), itemCmp);
	for (int i = 0; i < n;) {
		int j = i;
		uint32_t rows[16] = {0};
		while (j < n && items[j].key == items[i].key) {
			rows[items[j].z] |= 1u << items[j].x;
			j++;
		}
		int y = (items[i].key & 0xFFFF) - 512, kind = items[i].key >> 16;
		range(o, y, y);
		rects(o, rows, b, x0, z0, bottoms ? LM_F_BOTTOM : LM_F_TOP, kind, y);
		i = j;
	}
}

typedef struct {
	int n;
	int lo[MAX_IV * 2], hi[MAX_IV * 2];
	unsigned char kind[MAX_IV * 2];
	unsigned char used[MAX_IV * 2];
} Pieces;

// The floor a skirt on cell (cx, cz) (tile cells) facing (dx, dz) reaches down to: under its own ground and the ground of
// the cells across the seam (this level's own estimate of what another level, or the real terrain, has there: a coarser
// level samples the same terrain, the real terrain is these very cells), less their relief (a finer level's cells can dip
// that much between this level's samples). LM_DEEP when the cell across is unknown.
static int skirtFloor(const Col *cols, int cx, int cz, int dx, int dz) {
	const Col *a = &cols[(cz + 1) * REGION + (cx + 1)];
	int lo = a->hi[0], hi = a->hi[0];
	// Water meeting water at the same surface across the seam: no gap to hide, so no skirt. Under the surface it showed through
	// the real terrain's water as pale columns hanging from the seam (noahdunnagan/mcopt#14).
	if (a->wet && cx + dx >= -1 && cz + dz >= -1 && cx + dx <= LM_TILE && cz + dz <= LM_TILE) {
		const Col *b = &cols[(cz + dz + 1) * REGION + (cx + dx + 1)];
		if (b->n > 0 && b->wet && b->hi[0] == a->hi[0]) return a->hi[0];
	}
	int ax = dz != 0 ? 1 : 0, az = dx != 0 ? 1 : 0;
	for (int k = -1; k <= 1; k++) {
		int nx = cx + dx + k * ax, nz = cz + dz + k * az;
		if (nx < -1 || nz < -1 || nx > LM_TILE || nz > LM_TILE) continue;
		const Col *n = &cols[(nz + 1) * REGION + (nx + 1)];
		if (n->n <= 0) {
			if (k == 0) return LM_DEEP;
			continue;
		}
		if (n->hi[0] < lo) lo = n->hi[0];
		if (n->hi[0] > hi) hi = n->hi[0];
	}
	int margin = hi - lo > 4 ? hi - lo : 4;
	return lo - margin;
}

// Walls of the block's cells facing one direction, in the planes from `first` to `last` (cell columns of the block across the
// direction), merged along it. skirt: toward neighbors drawn by another level or by the real terrain (down to skirtFloor)
// instead of this level's.
static void walls(Out *o, const Col *cols, int b, int x0, int z0, int face, int first, int last, int skirt) {
	int dx = face == LM_F_XP ? 1 : face == LM_F_XN ? -1 : 0, dz = face == LM_F_ZP ? 1 : face == LM_F_ZN ? -1 : 0;
	int alongX = dx == 0;   // z-facing walls run along x
	for (int i = first; i <= last; i++) {
		Pieces p[16];
		for (int r = 0; r < b; r++) {
			int x = alongX ? r : i, z = alongX ? i : r;
			const Col *a = &cols[(z0 + z + 1) * REGION + (x0 + x + 1)];
			Col floorCol = {1, {NEG_INF}, {0}, {LM_K_GROUND}};
			if (skirt) floorCol.hi[0] = skirtFloor(cols, x0 + x, z0 + z, dx, dz);
			const Col *nb = skirt ? &floorCol : &cols[(z0 + z + dz + 1) * REGION + (x0 + x + dx + 1)];
			p[r].n = a->n > 0 ? faceDiff(a, nb, p[r].lo, p[r].hi, p[r].kind) : 0;
			memset(p[r].used, 0, sizeof p[r].used);
		}
		// the wall's plane in tile cells
		int plane = (alongX ? z0 : x0) + i + ((dx > 0 || dz > 0) ? 1 : 0);
		for (int r = 0; r < b; r++) {
			for (int k = 0; k < p[r].n; k++) {
				if (p[r].used[k]) continue;
				int lo = p[r].lo[k], hi = p[r].hi[k], kind = p[r].kind[k];
				// a skirt's ground piece (its lowest): one cell each, its foot found on the GPU
				int dynamic = skirt && k == 0 && kind != LM_K_CROWN;
				if (dynamic) {
					if (alongX) emitf(o, x0 + r, plane, 1, 1, face, kind, lo, hi, 0, 1);
					else emitf(o, plane, z0 + r, 1, 1, face, kind, lo, hi, 0, 1);
					continue;
				}
				int len = 1;
				for (int s = r + 1; s < b; s++) {
					int hit = -1;
					for (int j = 0; j < p[s].n; j++) {
						if (!p[s].used[j] && p[s].lo[j] == lo && p[s].hi[j] == hi && p[s].kind[j] == kind) {
							hit = j;
							break;
						}
					}
					if (hit < 0) break;
					p[s].used[hit] = 1;
					len++;
				}
				if (!skirt) range(o, lo, hi);
				if (alongX) emit(o, x0 + r, plane, len, 1, face, kind, lo, hi, 0);
				else emit(o, plane, z0 + r, len, 1, face, kind, lo, hi, 0);
			}
		}
	}
}

// Level 0's plants: two quads on each cell that has one (the cross model's diagonals).
static void plants(Out *o, const LmIn *in, int b, int x0, int z0) {
	if (!in->plantA || in->level != 0) return;
	int m = (1 << in->logN) - 1;
	for (int z = 0; z < b; z++) {
		for (int x = 0; x < b; x++) {
			int ax = in->tx * LM_TILE + x0 + x, az = in->tz * LM_TILE + z0 + z;
			size_t idx = ((size_t) (az & m) << in->logN) | (size_t) (ax & m);
			uint32_t g = in->geom[idx];
			int blocks = (int) ((g >> 22) & 3u);
			if (!(g & GEOM_VALID) || (g & GEOM_CROWN) || blocks == 0) continue;
			uint32_t pa = in->plantA[idx], pb = in->plantB[idx];
			if ((pa & 1023u) == 0) continue;
			int y = (int) (g & 0xFFFu) - 512;
			int ox = (int) ((pb >> 16) & 15u), oz = (int) ((pb >> 20) & 15u), oy = (int) ((pb >> 24) & 15u);
			range(o, y - 1, y + blocks);
			for (int q = 0; q < 2; q++) emit(o, x0 + x, z0 + z, (q | (blocks - 1) << 1) + 1, ox + 1, LM_F_PLANT, LM_K_GROUND, y, y + blocks, oz | oy << 4);
		}
	}
}

int lm_mesh(const LmIn *in, uint32_t *header, uint32_t *quads, int cap) {
	int b = in->block == 16 ? 16 : 8, nb = LM_TILE / b;
	Col *cols = malloc(sizeof(Col) * REGION * REGION);
	if (!cols) return -1;
	int ax0 = in->tx * LM_TILE, az0 = in->tz * LM_TILE;
	for (int z = -1; z <= LM_TILE; z++) {
		for (int x = -1; x <= LM_TILE; x++) {
			Col *c = &cols[(z + 1) * REGION + (x + 1)];
			int outX = x < 0 ? 2 : x >= LM_TILE ? 1 : 0, outZ = z < 0 ? 8 : z >= LM_TILE ? 4 : 0;
			if (outX && outZ) {
				// corners: no wall ever faces them
				c->n = -1;
				continue;
			}
			int need = outX | outZ;
			if (need && !(in->neighbors & need)) {
				c->n = -1;
				continue;
			}
			column(in, ax0 + x, az0 + z, c);
		}
	}
	Out o = {quads, 0, cap, 0, 0, 0};
	for (int bz = 0; bz < nb; bz++) {
		for (int bx = 0; bx < nb; bx++) {
			uint32_t *h = header + (size_t) (bz * nb + bx) * LM_HDR;
			int x0 = bx * b, z0 = bz * b;
			o.minY = 1 << 20;
			o.maxY = -(1 << 20);
			int groupMax[LM_GROUPS];
			for (int g = 0; g < LM_G_INFO; g++) {
				int start = o.count;
				switch (g) {
				case LM_G_TOP: flats(&o, cols, b, x0, z0, 0); break;
				case LM_G_BOTTOM: flats(&o, cols, b, x0, z0, 1); break;
				case LM_G_XP: walls(&o, cols, b, x0, z0, LM_F_XP, 0, b - 2, 0); break;
				case LM_G_XN: walls(&o, cols, b, x0, z0, LM_F_XN, 1, b - 1, 0); break;
				case LM_G_ZP: walls(&o, cols, b, x0, z0, LM_F_ZP, 0, b - 2, 0); break;
				case LM_G_ZN: walls(&o, cols, b, x0, z0, LM_F_ZN, 1, b - 1, 0); break;
				case LM_G_BXP: walls(&o, cols, b, x0, z0, LM_F_XP, b - 1, b - 1, 0); break;
				case LM_G_BXN: walls(&o, cols, b, x0, z0, LM_F_XN, 0, 0, 0); break;
				case LM_G_BZP: walls(&o, cols, b, x0, z0, LM_F_ZP, b - 1, b - 1, 0); break;
				case LM_G_BZN: walls(&o, cols, b, x0, z0, LM_F_ZN, 0, 0, 0); break;
				case LM_G_SXP: walls(&o, cols, b, x0, z0, LM_F_XP, b - 1, b - 1, 1); break;
				case LM_G_SXN: walls(&o, cols, b, x0, z0, LM_F_XN, 0, 0, 1); break;
				case LM_G_SZP: walls(&o, cols, b, x0, z0, LM_F_ZP, b - 1, b - 1, 1); break;
				case LM_G_SZN: walls(&o, cols, b, x0, z0, LM_F_ZN, 0, 0, 1); break;
				case LM_G_PLANT: plants(&o, in, b, x0, z0); break;
				}
				int count = o.count - start;
				if (count > 4095) o.overflow = 1;
				h[g] = (uint32_t) start | (uint32_t) count << 20;
				// the group's highest point
				int gmax = LM_DEEP;
				for (int k = start; k < o.count && !o.overflow; k++) {
					uint32_t w1 = o.quads[(size_t) k * 2 + 1];
					int y0 = (int) (w1 & 0xFFFu) - 512, y1 = (int) ((w1 >> 12) & 0xFFFu) - 512;
					int top = y0 > y1 ? y0 : y1;
					if (top > gmax) gmax = top;
				}
				groupMax[g] = gmax + 512;
			}
			groupMax[LM_G_INFO] = 0;
			for (int k = 0; k < 8; k++) h[24 + k] = (uint32_t) groupMax[2 * k] | (uint32_t) groupMax[2 * k + 1] << 12;
			if (o.maxY < o.minY) o.minY = o.maxY = 0;
			int lo = o.minY < LM_DEEP ? LM_DEEP : o.minY, hi = o.maxY > 3583 ? 3583 : o.maxY;
			h[LM_G_INFO] = (uint32_t) (lo + 512) | (uint32_t) (hi + 512) << 16;
			// the 4 x 4 parts' lowest solid tops: a column's top, a crown cell's ground (the first interval's); and the highest
			// bottom of their real columns' solid runs (a camera under it may see through a cave or under an overhang)
			int qm[16], qb[16];
			for (int k = 0; k < 16; k++) qm[k] = 3583, qb[k] = NEG_INF;
			for (int z = 0; z < b; z++) {
				for (int x = 0; x < b; x++) {
					const Col *c = &cols[(z0 + z + 1) * REGION + (x0 + x + 1)];
					int k = (z * 4 / b) * 4 + x * 4 / b;
					int top = c->n > 0 && !c->clear ? c->hi[0] : LM_DEEP;
					if (top < qm[k]) qm[k] = top;
					if (c->n > 0 && !c->clear && c->bot > qb[k]) qb[k] = c->bot;
				}
			}
			for (int k = 0; k < 16; k++) qm[k] = (qm[k] < LM_DEEP ? LM_DEEP : qm[k]) + 512;
			// the bottoms as 8 bits (0: none; else y = 2e - 66, rounded up), nibbles over words 16-23 and 24-31's bits 24-31
			for (int k = 0; k < 16; k++) {
				int v = qb[k] + 66;
				qb[k] = qb[k] == NEG_INF ? 0 : v <= 2 ? 1 : (v + 1) / 2 > 255 ? 255 : (v + 1) / 2;
			}
			for (int k = 0; k < 8; k++) {
				h[16 + k] = (uint32_t) qm[2 * k] | (uint32_t) qm[2 * k + 1] << 12 | (uint32_t) (qb[2 * k] & 15) << 24 | (uint32_t) (qb[2 * k + 1] & 15) << 28;
				h[24 + k] |= (uint32_t) (qb[2 * k] >> 4) << 24 | (uint32_t) (qb[2 * k + 1] >> 4) << 28;
			}
		}
	}
	free(cols);
	if (o.overflow || o.count >= (1 << 20)) return -1;
	return o.count;
}
