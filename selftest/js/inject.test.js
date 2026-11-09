// 在 node 里用最小 DOM 桩跑一遍 inject.js，验证「跨 iframe 找密码框 + 填 + 点」
// 这条逻辑真的能走通（真机上它此前一次都没执行过）。
const fs = require('fs');
const path = require('path').join(__dirname, '../../app/src/main/assets/inject.js');
let src = fs.readFileSync(path, 'utf8')
  .replaceAll('__USER__', JSON.stringify('20230000000'))
  .replaceAll('__PASS__', JSON.stringify('p@ss word'));

function input(type, name, placeholder) {
  return {
    tagName: 'INPUT', type, name: name || '', id: '', placeholder: placeholder || '',
    value: '', children: [], clicked: false,
    getBoundingClientRect: () => ({ width: 100, height: 24 }),
    dispatchEvent: () => true,
    click() { this.clicked = true; }
  };
}
function button(text) {
  return {
    tagName: 'BUTTON', children: [], clicked: false, innerText: text, value: '',
    getBoundingClientRect: () => ({ width: 80, height: 30 }),
    dispatchEvent: () => true,
    click() { this.clicked = true; }
  };
}

const loginBtn = button('立即登录');
const userIn = input('text', 'username', '请输入学号');
const passIn = input('password', 'password', '请输入密码');

const innerDoc = {
  body: { innerText: '校园网用户登录' },
  querySelectorAll(sel) {
    if (sel.indexOf('input[type=checkbox]') === 0) return [];
    if (sel.indexOf('input[type=submit]') === 0 || sel.indexOf('iframe') === 0) {
      if (sel.indexOf('iframe') === 0) return [];
      return [loginBtn];
    }
    if (sel === 'input') return [userIn, passIn];
    return [];
  }
};
const innerWin = { document: innerDoc, location: { href: 'http://10.0.0.1/eportal/index.jsp?wlanuserip=10.0.0.123' } };

const frameEl = { contentWindow: innerWin, src: 'http://10.0.0.1/eportal/index.jsp?wlanuserip=10.0.0.123' };

// 真机 15:28 的现场：连点三次，页面纹丝不动，随后弹了个 layer 提示框。
// 那次我们看不到它写了什么，所以这里专门放一个弹层，验证它会被抓回来。
const popupEl = {
  tagName: 'DIV', children: [], innerText: '您已在线，请勿重复登录',
  getBoundingClientRect: () => ({ width: 200, height: 40 }),
  getAttribute: () => null
};

const topDoc = {
  body: { innerText: '微信报修：关注「广东财经大学校园网」 校园网用户登录' },
  querySelectorAll(sel) {
    if (sel.indexOf('iframe') === 0) return [frameEl];
    if (sel.indexOf('.layui-layer-content') >= 0) return [popupEl];
    if (sel === 'input') return [];          // 顶页没有输入框，正是真机的情况
    return [];
  }
};

const topWin = {
  document: topDoc,
  location: { href: 'http://10.0.0.1/a79.htm?wlanacip=100%2e64%2e13%2e18' },
  getComputedStyle: () => ({ display: 'block', visibility: 'visible', opacity: '1' }),
  HTMLInputElement: { prototype: {} }
};

global.window = topWin;
global.document = topDoc;
global.getComputedStyle = topWin.getComputedStyle;
global.Event = class { constructor(t) { this.type = t; } };
global.KeyboardEvent = class { constructor(t, o) { this.type = t; Object.assign(this, o || {}); } };

const result = eval(src);
console.log('返回值 :', result);
const r = JSON.parse(result);
console.log('状态   :', r.s);
console.log('页面提示:', r.m || '(空)');
console.log('页面请求:', (r.n || []).join(' ／ ') || '(空)');
console.log('账号填 :', userIn.value === '20230000000' ? '✅' : '❌ ' + userIn.value);
console.log('密码填 :', passIn.value === 'p@ss word' ? '✅' : '❌ ' + passIn.value);
console.log('点了按钮:', loginBtn.clicked ? '✅ ' + loginBtn.innerText : '❌');
console.log('帧结构 :');
r.f.forEach(f => console.log('   ', f.p, 'input=' + f.n, 'pw=' + f.pw, '|', f.u, '|', f.t));

const ok = r.s.startsWith('CLICK') && userIn.value === '20230000000'
  && loginBtn.clicked && r.f.length === 2 && r.f[1].pw === 1
  // 新增：弹层文字必须被带回来 —— 这是 15:28 那次"点了没反应"唯一能查的线索
  && r.m.indexOf('您已在线') >= 0;
console.log('\n结论   :', ok ? '全部通过' : '有用例失败');
process.exit(ok ? 0 : 1);
