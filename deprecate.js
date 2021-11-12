const version = (async() => {
	let req = await fetch("https://raw.githubusercontent.com/runelite/plugin-hub/master/runelite.version");
	let version = await req.text();
	return version.trim();
})();

const root = "https://abex.sauville.org/externalplugin/"

const manifest = (async() => {
	let req = await fetch(`${root}${await version}/manifest.js`);
	let buf = new DataView(await req.arrayBuffer());
	let skip = 4 + buf.getUint32(0);
	let text = new TextDecoder("utf-8").decode(new Uint8Array(buf.buffer.slice(skip)));
	return JSON.parse(text);
})();

async function readPluginApi(manifest) {
	let req = await fetch(`${root}${await version}/${manifest.internalName}/${manifest.commit}.api`);
	let data = pako.inflate(new Uint8Array(await req.arrayBuffer()));
	let text = new TextDecoder("utf-8").decode(data);
	return text.split("\n");
}

async function amap(limit, array, asyncMapper) {
	let out = new Array(array.length);
	let todo = new Array(array.length).fill(0).map((_, i) => i);
	await Promise.all(new Array(limit).fill(0).map(async () => {
		for (; todo.length > 0; ) {
			let i = todo.pop();
			out[i] = await asyncMapper(array[i]);
		}
	}));
	return out;
}

const byUsage = (async() => {
	let out = new Map();
	await amap(64, await manifest, async (plugin) => {
		let api = await readPluginApi(plugin);
		for (let k of api) {
			if (k == "") {
				continue
			}
			let ps = out.get(k);
			if (!ps) {
				out.set(k, ps = [])
			}
			ps.push(plugin.internalName);
		}
	});
	let es = [...out.entries()];
	es.sort(([a], [b]) => a.localeCompare(b));
	return es
})();

class AutoMap extends Map {
	constructor(factory) {
		super()
		this.factory = factory;
	}
	get(key) {
		let v = super.get(key);
		if (v === undefined) {
			this.set(key, v = this.factory(key));
		}
		return v;
	}
}

(async () => {
	let mf = await manifest;
	let usages = await byUsage;

	document.body.addEventListener("click", async ev => {
		if (ev?.target?.classList?.contains("plugin")) {
			ev.preventDefault();
			let name = ev.target.textContent;
			let req = await fetch(`https://raw.githubusercontent.com/runelite/plugin-hub/master/plugins/${name}`);
			let text = await req.text();
			let prop = {};
			for (let line of text.split("\n")) {
				let kv = line.split("=", 2);
				if (kv.length == 2) {
					prop[kv[0]] = kv[1];
				}
			}
			window.open(`${prop.repository.replace(/\.git$/, "")}/tree/${prop.commit}`);
		}
	});

	const List = {
		props: {
			list: {},
			name: {},
			active: {
				type: Boolean,
				default: false,
			}
		},
		data() {
			return {
				active_: this.active,
			}
		},
		template: `
<div class="list">
	<div class="header" @click="active_=!active_">[ {{active_ ? "-" : "+"}} ] {{list.length}} {{name}}</div>
	<ul v-if="active_">
		<li v-for="item of list">
			<slot :item="item"></slot>
		</li>
	</ul>
</div>
`,
	};
	
	class Search {
		static numEntries = 1;
		constructor(regex) {
			this.id = Search.numEntries++;
			this.regex = regex || "";
		}

		set regex(value) {
			this._regex = value;
			let error = "";
			let allMatches = new Set();
			// Map<Group, Map<Value, Set<Plugin>>>
			let groups = new AutoMap(() => new AutoMap(() => new Set()));
			let symbols = [];
			if (value != "" && value != "^")
			{
				try {
					let re = new RegExp(value);
					for (let [sym, plugins] of usages) {
						let match = re.exec(sym);
						if (match) {
							symbols.push(sym);
							for (let plugin of plugins) {
								allMatches.add(plugin)
							}
							if (match.groups) {
								for (let group in match.groups) {
									let groupMatches = groups.get(group).get(match.groups[group]);
									for (let plugin of plugins) {
										groupMatches.add(plugin)
									}
								}
							}
						}
					}
					
				} catch (e) {
					console.error(e);
					error = e + "";
				}
			}

			this.error = error;
			this.allMatches = Object.freeze([...allMatches]);
			this.symbols = Object.freeze(symbols);
			if (groups.size > 0) {
				groups = [...groups.entries()].map(([name, group]) => {
					group = [...group.entries()].map(([name, plugins]) => {
						plugins = [...plugins];
						plugins.sort();
						return Object.freeze([name, Object.freeze(plugins)]);
					})
					group.sort(([, a], [, b]) => b.length - a.length)
					return Object.freeze([name, Object.freeze(group)]);
				});
				groups.sort(([, a], [, b]) => b.length - a.length);
				this.groups = Object.freeze(groups);
			} else {
				this.groups = undefined;
			}
		}
		get regex() {
			return this._regex;
		}

		static component = {
			props: ["entry"],
			components: {List},
			template: `
<div class="search">
	<input v-model="entry.regex" placeholder="^Lnet/runelite/foo" @focus="entry.focused=true" @blur="entry.focused=false">
	<div v-if="entry.error" class="error">{{entry.error}}</div>
	<div v-if="!entry.error">
		<List v-if="entry.groups" v-for="grouping of entry.groups" :list="grouping[1]" :name="'groups by ' + grouping[0]" :active="true" v-slot="{item}">
			<List :list="item[1]" :name="item[0]" v-slot="{item}">
				<span class="plugin">{{item}}</span>
			</List>
		</List>
		<List :list="entry.allMatches" :active="!entry.groups" name="plugins" v-slot="{item}">
			<span class="plugin">{{item}}</span>
		</List>
		<List :list="entry.symbols" name="symbols" v-slot="{item}">
			<code>{{item}}</code>
		</List>
	</div>
</div>
`,
		}
	}

	Vue.createApp({
		data() {
			let entries;
			try {
				let hash = window.location.hash;
				hash = hash.substr(1);
				hash = atob(hash);
				hash = JSON.parse(hash);
				entries = hash.map(v => new Search(v));
			} catch (e) {
				console.log("loading hash:", e);
			}
			return {
				entries: entries || [new Search("^Lnet/runelite/api/events/(?<name>.*Object)Spawned")],
			}
		},
		template: `
<Search v-for="entry of entries" :key="entry.id" :entry="entry"></Search>
`,
		components: {
			Search: Search.component,
		},
		created() {
			this.$watch("entries", () => {
				history.replaceState(undefined, undefined, "#" + btoa(JSON.stringify(this.entries.map(s => s.regex))));

				if (this.entries.length == 0 || this.entries[this.entries.length - 1].regex != "") {
					this.entries.push(new Search());
				}
				for (let i = this.entries.length - 2; i >= 0; i--) {
					if (this.entries[i].regex == "" && !this.entries[i].focused) {
						this.entries.splice(i, 1);
					}
				}
			}, {deep: true});
		},
		watch: {
		},
		computed: {
		},
		methods: {
		},
	}).mount("#app");
})().catch(e => console.error(e));