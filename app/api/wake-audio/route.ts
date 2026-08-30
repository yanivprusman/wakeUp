import { NextRequest } from 'next/server';
import { execFile } from 'child_process';
import { promisify } from 'util';
import { readFile, unlink } from 'fs/promises';
import { tmpdir } from 'os';
import { join } from 'path';

const execFileAsync = promisify(execFile);

const KOKORO_RENDER = '/opt/dev/claude-voice/bin/kokoro-render';
const VOICE = 'af_heart';

/**
 * The morning briefing, rendered to a WAV.
 *
 * The phone downloads this WHEN THE ALARM IS SET and keeps the bytes. That is the whole
 * design: at 06:00 the phone needs no desktop, no Wi-Fi and no server — it plays a file it
 * already has. Fetching at fire time would put a network round-trip on the one code path
 * that must never have one.
 *
 * Why a spoken briefing at all: a brain learns to sleep through a sound it has heard two
 * hundred times. It cannot learn to sleep through a sentence it has never heard before, and
 * a briefing is different every day by construction.
 *
 * `at` is WHEN THE ALARM WILL RING, and it is required — not the server's clock, which is
 * what this used to read. Rendering happens at bedtime and playback happens the next morning,
 * so the server clock produced "It is 21:00 on Sunday" and then said it to someone standing
 * in the dark at 04:40 on Monday. A caller that cannot say when the alarm rings cannot be
 * given a sentence that names the time, so it is a 400 rather than a confident lie.
 *
 * `tz` is the PHONE's zone. Server and phone sit on the same LAN in the same country today,
 * and the day this stops being true is not the morning to discover that the briefing is
 * rendered in the server's idea of Monday.
 */
function briefing(at: Date, label: string, tz: string | undefined): string {
  const day = at.toLocaleDateString('en-US', { weekday: 'long', timeZone: tz });
  const time = at.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', timeZone: tz });
  const opener = label.trim() ? `${label.trim()}.` : 'Good morning.';
  return [
    opener,
    `It is ${time} on ${day}.`,
    'You are awake now, and the sooner you prove it the sooner this stops.',
  ].join(' ');
}

export async function GET(req: NextRequest) {
  const params = req.nextUrl.searchParams;
  const label = params.get('label') ?? '';
  const override = params.get('text')?.trim();
  // Number(null) is 0 and Number('') is 0, both perfectly finite — so a MISSING `at` would
  // sail through a bare isFinite check and render the epoch. Absent has to be its own case.
  const atRaw = params.get('at');
  const at = atRaw ? Number(atRaw) : NaN;

  if (!override && !(Number.isFinite(at) && at > 0)) {
    return Response.json(
      { error: '`at` (epoch millis of the moment the alarm rings) is required' },
      { status: 400 },
    );
  }

  const dest = join(tmpdir(), `wakeup-${Date.now()}-${Math.random().toString(36).slice(2)}.wav`);
  try {
    // Inside the try: an unknown `tz` makes Intl throw, and that belongs in the same error
    // path as a renderer that failed — not in an unhandled 500 with no detail.
    const text = override || briefing(new Date(at), label, params.get('tz') || undefined);

    // Text on stdin, voice and destination as arguments — kokoro-render writes nothing and
    // exits non-zero on failure, so an empty file can never be served as a briefing.
    await execFileAsync('bash', ['-c',
      `printf '%s' ${JSON.stringify(text)} | ${KOKORO_RENDER} ${VOICE} ${JSON.stringify(dest)}`,
    ], { timeout: 120_000, maxBuffer: 4 * 1024 * 1024 });

    const wav = await readFile(dest);
    if (wav.length === 0) throw new Error('renderer produced an empty file');

    return new Response(new Uint8Array(wav), {
      headers: {
        'Content-Type': 'audio/wav',
        'Content-Length': String(wav.length),
        'X-Wake-Text': encodeURIComponent(text),
        'Cache-Control': 'no-store',
      },
    });
  } catch (err) {
    console.error('[wake-audio] render failed:', err);
    return Response.json(
      { error: 'render failed', detail: err instanceof Error ? err.message : String(err) },
      { status: 500 },
    );
  } finally {
    await unlink(dest).catch(() => {});
  }
}
