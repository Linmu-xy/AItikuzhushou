const { spawnSync } = require('node:child_process');
const { mkdirSync, statSync, readFileSync } = require('node:fs');
const { resolve, dirname } = require('node:path');
const env = { ...process.env, PROMPTFOO_DISABLE_TELEMETRY: '1', PROMPTFOO_DISABLE_UPDATE: '1',
  PROMPTFOO_CONFIG_DIR: resolve(__dirname, '.promptfoo'), PROMPTFOO_CACHE_ENABLED: 'false',
  PROMPTFOO_DISABLE_REMOTE_GENERATION: 'true', CI: '1' };
// The offline harness has no reason to inherit model credentials. Do not load the project .env.
for (const key of Object.keys(env)) if (/(API_KEY|ACCESS_TOKEN|AUTH_TOKEN|PASSWORD|SECRET)/i.test(key)) delete env[key];
function run(command, args, cwd) {
  const result = spawnSync(command, args, { cwd, env, stdio: 'inherit', windowsHide: true });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
}
const started = Date.now();
if (process.platform === 'win32') run('pwsh', ['-NoProfile', '-File', resolve(__dirname, 'run-java.ps1')], __dirname);
else run(env.TIKU_MAVEN_CMD || 'mvn', ['-q', '-Dtest=AssessmentRegressionFixtureTests', 'test'], resolve(__dirname, '../../backend'));
const exportFile = resolve(__dirname, '../../backend/target/question-quality-eval.json');
if (statSync(exportFile).mtimeMs < started - 1000) throw new Error('Refusing stale Java results');
const snapshot = JSON.parse(readFileSync(exportFile, 'utf8'));
if (!snapshot.rows?.length || new Set(snapshot.rows.map(row => row.id)).size !== snapshot.rows.length)
  throw new Error('Missing or duplicate regression outcomes');
mkdirSync(resolve(__dirname, 'results'), { recursive: true });
const packageFile = resolve(__dirname, 'node_modules/promptfoo/package.json');
const bin = JSON.parse(readFileSync(packageFile, 'utf8')).bin.promptfoo;
run(process.execPath, [resolve(dirname(packageFile), bin), 'eval', '-c', 'promptfooconfig.cjs',
  '--no-cache', '--no-share', '--no-write', '--no-progress-bar', '--no-table',
  '-o', 'results/latest.json', 'results/latest.html'], __dirname);
