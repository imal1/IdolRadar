function firstDefined() {
  for (var index = 0; index < arguments.length; index += 1) {
    if (arguments[index] !== undefined && arguments[index] !== null) {
      return arguments[index];
    }
  }
  return undefined;
}

function normalizeIdol(raw, index) {
  raw = raw || {};
  var name = String(raw.name || '未命名');
  var plainName = name.replace(/[（(【\[].*$/, '').trim() || name;
  var characters = Array.from ? Array.from(plainName) : plainName.split('');
  var sourceCount = Number(firstDefined(raw.sourceCount, raw.sourcesCount, raw.enabledSourceCount, 0));
  return {
    id: String(raw._id || raw.id || raw.idolId || ''),
    name: name,
    initial: characters[characters.length - 1] || '星',
    avatar: typeof raw.avatar === 'string' ? raw.avatar : '',
    avatarVisible: Boolean(raw.avatar),
    bio: String(raw.bio || '等待发现更多闪光动态'),
    sourceCount: isNaN(sourceCount) ? 0 : sourceCount,
    colorIndex: Number(index || 0) % 4
  };
}

module.exports = {
  normalizeIdol: normalizeIdol,
  firstDefined: firstDefined
};
