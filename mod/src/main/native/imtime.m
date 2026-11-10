// Imagine Metal: where the GPU's time goes, per pass.
#include <stdio.h>
//
// Every IMT_EVERY-th frame is sampled: every encoder of that frame (render, compute, blit, in the frame's command buffer and in the
// pre one) gets GPU timestamps at its stage boundaries (Apple GPUs only sample there, MTLCounterSamplingPointAtStageBoundary), into
// a counter sample buffer of its own (attachment 1; mcopt's trace keeps attachment 0). Each encoder is booked under the pass it
// draws (a tag: the shadow maps, the terrain, the entities...). Our own passes say which they are; the game's passes take the tag
// Java set last (im_time_tag), and on a sampled frame the game's main pass is cut into encoders of its own at each tag change
// (the main pass draws the terrain, the entities and the translucent layer in one render encoder otherwise).
// When the frame's command buffer completes, the samples are read and summed per tag; Java reads the averages (ms a frame) every
// few seconds for the perf line (Perf) and the benchmark.
//
// Attribution: encoders overlap on the GPU (the next one's vertex stage under this one's fragment stage, and an encoder's first
// sample can come long before its work, while it waits behind the ones before it), so an encoder's own first-to-last span says
// little (they summed to twice the frame). Instead the submit's encoders are put in the order the GPU runs them (the pre command
// buffer's, then the main one's, each in encoding order) and each is booked the time from where the work before it ended (the
// latest end so far) to its own end: what it added to the frame. Those parts partition the submit's span (its first sample to its
// last end) exactly, with the gaps where the GPU had nothing (an encoder starting after everything before it had ended) booked
// apart as idle: tags + idle = span, by construction. Encoders not timed (debug-only ones) land in the next timed encoder.
// The frame before a sampled one is timed too (a lead: nothing booked, no pass cut), only for where its GPU work ended: the GPU
// runs frames into each other (the CPU is a few frames ahead), so a sampled frame's first encoders start long before they can
// do anything, behind the previous frame's work (its span read 41.6 ms at a 7.4 ms frame). The sampled frame counts from where
// the work before it ended; what an encoder did under the previous frame's work is that frame's, not booked.
// And a timed submit runs alone: the submit after a lead or a sampled one waits on the GPU (an MTLEvent) until it has ended. Where
// the GPU has room (shaders off: 136 fps, the GPU far from busy) it otherwise runs the next frames under a sampled one, which
// stretched that frame to 18 ms of a 7.3 ms frame: its start-to-end wasn't its cost. Costs a little overlap every sixth frame.
#import "mcmetal.h"
#include <os/lock.h>
#include <stdatomic.h>

#define IMT_TAGS 24
#define IMT_GROUPS 256
#define IMT_SLOTS 6
#define IMT_EVERY 6

static id<MTLCounterSampleBuffer> imtBuf[IMT_SLOTS];
static _Atomic int imtBusy[IMT_SLOTS];
static unsigned char imtTagOf[IMT_SLOTS][IMT_GROUPS];
static unsigned char imtPreOf[IMT_SLOTS][IMT_GROUPS];  // the group is in the pre command buffer (runs before the main one)
static id imtPreCb;  // the pre command buffer now being recorded (unretained; mcmetal.m mc_pre), nil none
// the GPU's clock when the slot was taken: a sample buffer keeps what earlier uses wrote wherever this one wrote nothing (a stage that
// didn't run), so anything older than this is stale (it made encoders look hundreds of ms long)
static MTLTimestamp imtBase[IMT_SLOTS];
static int imtSlot = -1;   // the slot this submit samples into (-1: this submit isn't sampled)
static int imtLead;        // this submit is a lead (the frame before a sampled one): only its end is kept
static unsigned char imtLeadOf[IMT_SLOTS];
static _Atomic uint64_t imtPrevEnd;  // the GPU time the last timed submit's work ended (completion handlers, in queue order)
static int imtCount;       // groups used in it
static int imtOn;          // set by Java (Perf): sampling on
static int imtTried, imtOk;
static long imtFrame;
static int imtPresented;   // this submit presented (mc_present*)
static int imtRenderTag = -1;  // the tag of the render encoder now open (mcmetal.m openRender), -1 none
static MTLTimestamp imtCpu0, imtGpu0;
static id<MTLDevice> imtDevice;
static id<MTLEvent> imtEvent;     // a timed submit signals it when done; the next submit's command buffers wait for that
static uint64_t imtEventVal;      // the last value signalled
static uint64_t imtWaitVal;       // what this submit's command buffers wait for (0: nothing)

int imtTag;  // the tag the game's own passes are booked under now

static os_unfair_lock imtLock = OS_UNFAIR_LOCK_INIT;
static double imtSum[IMT_TAGS];  // µs summed over the sampled frames since the last read
static double imtSpan;           // the frames' spans summed (µs)
static int imtFrames;            // sampled frames (that presented) since the last read
static int imtEncoders;
static double imtIdle;           // the spans' GPU-idle gaps summed (µs)
static int imtDropped;           // encoders not timed because a slot was full

static int imtInit(Ctx *ctx) {
	if (imtTried) return imtOk;
	imtTried = 1;
	id<MTLDevice> device = ctx->device;
	if (![device supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary]) {
		printf("[Imagine Metal] pass timing: this GPU can't sample timestamps at stage boundaries\n");
		return 0;
	}
	@autoreleasepool {
		for (id<MTLCounterSet> set in device.counterSets) {
			if (![set.name isEqualToString:MTLCommonCounterSetTimestamp]) continue;
			for (int i = 0; i < IMT_SLOTS; i++) {
				MTLCounterSampleBufferDescriptor *d = [[MTLCounterSampleBufferDescriptor new] autorelease];
				d.counterSet = set;
				d.storageMode = MTLStorageModeShared;
				d.sampleCount = IMT_GROUPS * 4;
				d.label = @"Imagine Metal pass timing";
				NSError *e = nil;
				imtBuf[i] = [device newCounterSampleBufferWithDescriptor:d error:&e];
				if (!imtBuf[i]) {
					printf("[Imagine Metal] pass timing: no sample buffer (%s)\n", e ? e.localizedDescription.UTF8String : "?");
					return 0;
				}
			}
			[device sampleTimestamps:&imtCpu0 gpuTimestamp:&imtGpu0];
			imtDevice = [device retain];
			imtEvent = [device newEvent];
			imtOk = 1;
			printf("[Imagine Metal] pass timing on (every %d frames)\n", IMT_EVERY);
			return 1;
		}
	}
	printf("[Imagine Metal] pass timing: no timestamp counter set\n");
	return 0;
}

void im_time_enable(int on) { imtOn = on; }

int imt_sampling(void) { return imtSlot >= 0 && !imtLead; }

// The next group's first sample index (4 a group), booked under tag; -1 when this submit isn't sampled or the slot is full.
// cb: the encoder's command buffer (nil: the main one); a pre one's encoders default to culling.
static int imtGroup(int tag, id cb) {
	if (imtSlot < 0) return -1;
	if (imtCount >= IMT_GROUPS) {
		if (!imtLead) imtDropped++;
		return -1;
	}
	int g = imtCount++;
	int pre = cb != nil && cb == imtPreCb;
	imtTagOf[imtSlot][g] = (unsigned char) (tag >= 0 ? tag : pre ? IMT_CULL : imtTag);
	imtPreOf[imtSlot][g] = (unsigned char) pre;
	return g * 4;
}

void imt_pre_cmd(id<MTLCommandBuffer> c) { imtPreCb = c; }

void imt_cmd_begin(id<MTLCommandBuffer> c) {
	if (imtWaitVal && imtEvent) [c encodeWaitForEvent:imtEvent value:imtWaitVal];
}

// A render pass descriptor's timestamps (attachment 1), under tag (-1: the current one). Clears attachment 1 when not sampling, so
// a descriptor kept across frames (mcopt.cpu.pass) doesn't keep writing into an old slot.
void imt_rp(MTLRenderPassDescriptor *rp, int tag) {
	MTLRenderPassSampleBufferAttachmentDescriptor *s = rp.sampleBufferAttachments[1];
	int at = imtGroup(tag, nil);
	if (at < 0) {
		s.sampleBuffer = nil;
		return;
	}
	s.sampleBuffer = imtBuf[imtSlot];
	s.startOfVertexSampleIndex = (NSUInteger) at;
	s.endOfVertexSampleIndex = (NSUInteger) at + 1;
	s.startOfFragmentSampleIndex = (NSUInteger) at + 2;
	s.endOfFragmentSampleIndex = (NSUInteger) at + 3;
}

// mcopt's render encoders (openRender): the game's passes, under the current tag
void imt_open_render(MTLRenderPassDescriptor *rp) {
	imt_rp(rp, -1);
	imtRenderTag = rp.sampleBufferAttachments[1].sampleBuffer ? imtTag : -1;
}

void imt_cpd(MTLComputePassDescriptor *d, int tag, id<MTLCommandBuffer> c) {
	int at = imtGroup(tag, c);
	if (at < 0) return;
	d.sampleBufferAttachments[1].sampleBuffer = imtBuf[imtSlot];
	d.sampleBufferAttachments[1].startOfEncoderSampleIndex = (NSUInteger) at;
	d.sampleBufferAttachments[1].endOfEncoderSampleIndex = (NSUInteger) at + 1;
}

// A compute encoder on c booked under tag (-1: the current one). Retained, as mc_profiled_compute's.
id<MTLComputeCommandEncoder> imt_compute(id<MTLCommandBuffer> c, int tag, MTLDispatchType type) {
	@autoreleasepool {
		if (imtSlot < 0) return [[c computeCommandEncoderWithDispatchType:type] retain];
		MTLComputePassDescriptor *d = [MTLComputePassDescriptor computePassDescriptor];
		d.dispatchType = type;
		imt_cpd(d, tag, c);
		return [[c computeCommandEncoderWithDescriptor:d] retain];
	}
}

// A blit encoder on c booked under tag (-1: the current one). Retained.
id<MTLBlitCommandEncoder> imt_blit(id<MTLCommandBuffer> c, int tag) {
	@autoreleasepool {
		if (imtSlot < 0) return [[c blitCommandEncoder] retain];
		int at = imtGroup(tag, c);
		if (at < 0) return [[c blitCommandEncoder] retain];
		MTLBlitPassDescriptor *d = [MTLBlitPassDescriptor blitPassDescriptor];
		d.sampleBufferAttachments[1].sampleBuffer = imtBuf[imtSlot];
		d.sampleBufferAttachments[1].startOfEncoderSampleIndex = (NSUInteger) at;
		d.sampleBufferAttachments[1].endOfEncoderSampleIndex = (NSUInteger) at + 1;
		return [[c blitCommandEncoderWithDescriptor:d] retain];
	}
}

void imt_present(void) { imtPresented = 1; }

// The tag for the game's passes from now on. On a sampled frame, a render encoder of the game's that is open under another tag is
// ended so the new tag gets an encoder of its own; inPass (a Java render pass is open, its draws continue in it): it's reopened on
// the same attachments, loaded, and 2 returned: the caller binds the pass's state again (MetalBridge.restorePass). 0 otherwise.
int im_time_tag(Enc *enc, int tag, int inPass) {
	if (tag < 0 || tag >= IMT_TAGS) return 0;
	imtTag = tag;
	if (imtSlot < 0 || imtLead || !mc_render_is_open(enc) || imtRenderTag == tag || imtRenderTag < 0) return 0;
	if (!inPass) {
		mc_frame_cmd(enc);  // (ends the open encoders; the next pass opens its own)
		imtRenderTag = -1;
		return 0;
	}
	return mc_render_split(enc) ? 2 : 0;
}

// Inside mc_enc_commit, just before the frame's command buffer c is committed: its samples are read when it completes; then the
// next submit's slot is chosen.
void imt_commit(Enc *enc, id<MTLCommandBuffer> c) {
	int presented = imtPresented;
	imtPresented = 0;
	// (a timed submit, lead or sampled: the next submit waits for it on the GPU, so nothing runs under it)
	imtWaitVal = 0;
	if (imtSlot >= 0 && imtEvent) {
		imtEventVal++;
		[c encodeSignalEvent:imtEvent value:imtEventVal];
		imtWaitVal = imtEventVal;
	}
	imtRenderTag = -1;
	imtPreCb = nil;  // (committed just before: mcmetal.m mc_enc_commit; the next submit's pre is a new one)
	imtTag = IMT_MISC;  // (the next submit's first passes: uploads, the lightmap, until the level's own say otherwise)
	if (imtSlot >= 0) {
		int slot = imtSlot, n = imtCount, lead = imtLeadOf[slot];
		MTLTimestamp base = imtBase[slot];
		imtSlot = -1;
		imtLead = 0;
		if (n == 0) {
			atomic_store(&imtBusy[slot], 0);
		} else {
			// (the slot's tag and pre tables stay as they are until the handler frees the slot)
			[c addCompletedHandler:^(id<MTLCommandBuffer> done) {
				MTLTimestamp cpu1, gpu1;
				[imtDevice sampleTimestamps:&cpu1 gpuTimestamp:&gpu1];
				double usPerTick = gpu1 > imtGpu0 ? (double) (cpu1 - imtCpu0) / (double) (gpu1 - imtGpu0) / 1000.0 : 0.001;
				double sum[IMT_TAGS] = {0};
				uint64_t first = 0, reach = 0, idle = 0;
				int used = 0;
				uint64_t prev = atomic_load(&imtPrevEnd);
				@autoreleasepool {
					NSData *data = [imtBuf[slot] resolveCounterRange:NSMakeRange(0, (NSUInteger) n * 4)];
					const MTLCounterResultTimestamp *ts = data.length >= (NSUInteger) n * 4 * sizeof(MTLCounterResultTimestamp) ? data.bytes : NULL;
					// the GPU's order: the pre command buffer's encoders (committed first), then the main one's, each in encoding order
					for (int pass = 0; ts && pass < 2; pass++) {
						for (int g = 0; g < n; g++) {
							if (imtPreOf[slot][g] != (pass == 0)) continue;
							uint64_t a = UINT64_MAX, b = 0;
							for (int k = 0; k < 4; k++) {
								uint64_t t = ts[g * 4 + k].timestamp;
								if (t == MTLCounterErrorValue || t == 0 || t < base) continue;
								if (t < a) a = t;
								if (t > b) b = t;
							}
							if (b <= a || a == UINT64_MAX) continue;
							if (b - a > (uint64_t) (1.0e6 / usPerTick)) continue;  // (over a second: not a real sample)
							if (!used) {
								// (from where the work before this submit ended, if it was still running when this one's first encoder
								// started: what ran under it is the earlier work's)
								first = reach = prev > a && prev - a < (uint64_t) (1.0e6 / usPerTick) ? prev : a;
							}
							used++;
							uint64_t from = reach;
							if (a > reach) {  // (the GPU had nothing to do until this one started)
								idle += a - reach;
								from = a;
							}
							if (b > from) sum[imtTagOf[slot][g]] += (double) (b - from) * usPerTick;
							if (b > reach) reach = b;
						}
					}
				}
				if (used) {
					uint64_t was = atomic_load(&imtPrevEnd);
					while (reach > was && !atomic_compare_exchange_weak(&imtPrevEnd, &was, reach)) {
					}
				}
				atomic_store(&imtBusy[slot], 0);
				if (!used || lead) return;  // (a lead: only its end)
				os_unfair_lock_lock(&imtLock);
				for (int t = 0; t < IMT_TAGS; t++) imtSum[t] += sum[t];
				imtSpan += (double) (reach - first) * usPerTick;
				imtIdle += (double) idle * usPerTick;
				imtEncoders += used;
				if (presented) imtFrames++;
				os_unfair_lock_unlock(&imtLock);
			}];
		}
	}
	if (presented) imtFrame++;
	// the next submit: sampled when its frame is one of every IMT_EVERY (a frame's submits all sampled, the presenting one last); the
	// frame before it a lead
	int phase = (int) (imtFrame % IMT_EVERY);
	if (imtOn && (phase == 0 || phase == IMT_EVERY - 1) && imtInit(enc->ctx)) {
		for (int i = 0; i < IMT_SLOTS; i++) {
			int expect = 0;
			if (atomic_compare_exchange_strong(&imtBusy[i], &expect, 1)) {
				imtSlot = i;
				imtCount = 0;
				imtLead = phase != 0;
				imtLeadOf[i] = (unsigned char) imtLead;
				MTLTimestamp cpuNow;
				[imtDevice sampleTimestamps:&cpuNow gpuTimestamp:&imtBase[i]];
				break;
			}
		}
	}
}

// The sampled frames' sums since the last read, and resets them: out[t] µs per tag, out[IMT_TAGS] the frames' GPU spans (µs),
// out[IMT_TAGS + 1] sampled frames, out[IMT_TAGS + 2] encoders, out[IMT_TAGS + 3] the spans' idle gaps (µs), out[IMT_TAGS + 4]
// encoders left untimed (slot full). imaginemetal.PassTimes keeps its own windows of them.
void im_time_read(double *out) {
	os_unfair_lock_lock(&imtLock);
	for (int t = 0; t < IMT_TAGS; t++) out[t] = imtSum[t];
	out[IMT_TAGS] = imtSpan;
	out[IMT_TAGS + 1] = imtFrames;
	out[IMT_TAGS + 2] = imtEncoders;
	out[IMT_TAGS + 3] = imtIdle;
	out[IMT_TAGS + 4] = imtDropped;
	for (int t = 0; t < IMT_TAGS; t++) imtSum[t] = 0.0;
	imtSpan = 0.0;
	imtFrames = 0;
	imtEncoders = 0;
	imtIdle = 0.0;
	imtDropped = 0;
	os_unfair_lock_unlock(&imtLock);
}
