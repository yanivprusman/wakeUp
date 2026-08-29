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
 */
function briefing(now: Date, label: string): string {
  const days = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
  const day = days[now.getDay()];
  const time = now.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
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
  const text = params.get('text')?.trim() || briefing(new Date(), label);

  const dest = join(tmpdir(), `wakeup-${Date.now()}-${Math.random().toString(36).slice(2)}.wav`);
  try {
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
