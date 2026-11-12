// Where the QuestCast signaling server lives.
// Leave empty when these pages are served by server.js itself.
// When the pages are hosted somewhere static (e.g. GitHub Pages), set this to
// the server's address, for example: 'https://questcast.example.com'
window.QUESTCAST_SERVER = '';

// True when the pages are on a static host and no relay has been configured.
window.questcastNeedsServer = () =>
  !window.QUESTCAST_SERVER && location.hostname.endsWith('.github.io');

window.questcastSocketUrl = () => {
  const base = new URL(window.QUESTCAST_SERVER || location.origin);
  base.protocol = base.protocol === 'https:' ? 'wss:' : 'ws:';
  base.pathname = '/ws';
  return base.href;
};
