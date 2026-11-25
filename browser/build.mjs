// 打包广场浏览器的内核宿主：把 Tapekit 内核（原样）+ 网关的页面模板与响应头 + host.js 打成一个内联脚本的 HTML，
// 写进两端：Android assets/tapekit/host.html、iOS Resources/tapekit-host.html。
//   TAPEKIT_DIR=<TapeKit 仓库> node browser/build.mjs        （默认 ~/Documents/TapeKit）
// 产物要提交进仓库（两端构建不依赖 TapeKit 仓库）；页头写着打包时的 TapeKit 提交号。
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const here = path.dirname(fileURLToPath(import.meta.url));
const tapekit = path.resolve(process.env.TAPEKIT_DIR || path.join(os.homedir(), 'Documents', 'TapeKit'));
const require = createRequire(path.join(tapekit, 'package.json'));
const esbuild = require('esbuild');

const git = (...a) => execFileSync('git', ['-C', tapekit, ...a], { encoding: 'utf8' }).trim();
const commit = git('rev-parse', '--short', 'HEAD');
const dirty = git('status', '--porcelain', '--', 'kernel', 'sw-gateway');
if (dirty && !process.env.ALLOW_DIRTY) throw new Error('TapeKit 的 kernel/ 或 sw-gateway/ 有未提交改动，打包的就不是公开版本：\n' + dirty);

const out = await esbuild.build({
  entryPoints: [path.join(here, 'host.js')], bundle: true, write: false, format: 'iife', target: ['safari15', 'chrome100'],
  alias: { '@tapekit': tapekit }, loader: { '.txt': 'text', '.json': 'json', '.html': 'text' }, legalComments: 'none', charset: 'utf8', external: ['node:*'],
  define: { __TAPEKIT_VERSION__: JSON.stringify(commit) },
});
const js = out.outputFiles[0].text.replace('__TAPEKIT_VERSION__', commit).replace(/<\/script/gi, '<\\/script');
const page = `<!doctype html>\n<!-- TapeTape 广场浏览器内核宿主。Tapekit ${commit}（github.com/TapeOutProtocol/TapeKit），由 tapetape-v2/browser/build.mjs 生成，不要手改 -->\n<meta charset="utf-8">\n<script>\n${js}\n</script>\n`;
const targets = [
  path.join(here, '..', 'android', 'app', 'src', 'main', 'assets', 'tapekit', 'host.html'),
  path.join(here, '..', 'ios', 'TapeTape', 'Resources', 'tapekit-host.html'),
];
for (const t of targets) { fs.mkdirSync(path.dirname(t), { recursive: true }); fs.writeFileSync(t, page); console.log(`${path.relative(process.cwd(), t)}  ${(page.length / 1024).toFixed(0)} KB`); }
// 注入网页的钱包脚本（provider.js）拷到两端：去掉整行注释和缩进（网页能读到函数源码，注释是明显的特征）
const provider = fs.readFileSync(path.join(here, 'provider.js'), 'utf8')
  .split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('//')).join('\n') + '\n';
for (const t of [
  path.join(here, '..', 'android', 'app', 'src', 'main', 'assets', 'tapekit', 'provider.js'),
  path.join(here, '..', 'ios', 'TapeTape', 'Resources', 'tapetape-provider.js'),
]) { fs.writeFileSync(t, provider); console.log(`${path.relative(process.cwd(), t)}  ${(provider.length / 1024).toFixed(0)} KB`); }
console.log('Tapekit ' + commit);
