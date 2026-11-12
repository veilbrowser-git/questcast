// Where the QuestCast relay lives.
// Pages served by server.js itself (at home, or on the relay's own address)
// talk to that same server. Pages on a static host such as GitHub Pages
// can't run a relay, so they use the hosted one.
const HOSTED_RELAY = 'https://questcast.onrender.com';

window.QUESTCAST_SERVER = location.hostname.endsWith('.github.io') ? HOSTED_RELAY : '';

// True when the pages are on a static host and no relay has been configured.
window.questcastNeedsServer = () =>
  !window.QUESTCAST_SERVER && location.hostname.endsWith('.github.io');

window.questcastSocketUrl = () => {
  const base = new URL(window.QUESTCAST_SERVER || location.origin);
  base.protocol = base.protocol === 'https:' ? 'wss:' : 'ws:';
  base.pathname = '/ws';
  return base.href;
};
