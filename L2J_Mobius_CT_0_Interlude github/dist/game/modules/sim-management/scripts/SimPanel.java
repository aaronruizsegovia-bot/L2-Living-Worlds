package modules.simmanagement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.managers.PhantomBuddyManager;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.managers.PhantomManager.PartyRole;
import org.l2jmobius.gameserver.managers.PhantomManager.Recruit;
import org.l2jmobius.gameserver.managers.PhantomPartyManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * The Sim-Management window. Every button is a bypass that ends up calling the public API of the phantom managers -
 * the same lines you could type in party chat or whisper - so nothing here touches a stock class.
 * <p>
 * Layout rule: the title + tab bar + separator line are identical on every page, and every page below them is kept
 * short enough to fit the window without a scrollbar, so the tabs never move when you change page.
 */
final class SimPanel
{
	private static final int NEARBY_RANGE = 1500;
	private static final int MAX_HTML = 7800; // the client window budget is 8,192 characters
	private static final int TAB_W = 62; // 4 tabs fit the ~248 px wide window
	private static final int TAB_H = 21;
	// The client draws button textures at their native size and does not stretch them. L2UI.DefaultButton (the grey
	// metallic button) is about 64 x 17, so that is the size used; 3 per row fit the window with room to spare.
	// The selected button uses the flat light-grey texture (same as the selected tab), which never shows a cropped border.
	private static final int BTN_W = 64;
	private static final int BTN_H = 17;
	private static final String TAB_ON = "sek.cbui94"; // flat light-grey: the selected tab
	private static final String TAB_OFF = "sek.cbui92"; // flat darker grey: the other tabs
	private static final String BTN_ON = TAB_ON;
	private static final String BTN_OFF = "L2UI.DefaultButton";

	// What the panel last ordered, per player, only to highlight the active buttons (the managers keep the real state).
	private final Map<Integer, String> _stance = new ConcurrentHashMap<>(); // hold / follow / farm / assist (recruits join in assist)
	private final Map<Integer, Boolean> _camp = new ConcurrentHashMap<>(); // anchor (camp) on/off
	private final Map<Integer, Integer> _pullSize = new ConcurrentHashMap<>(); // 0 = no pulling, else 1..3
	private final Map<Integer, String> _puller = new ConcurrentHashMap<>(); // name of the sim ordered to pull
	private final Map<Integer, Boolean> _raidWait = new ConcurrentHashMap<>(); // true = wait for the tank on a raid
	private final Map<Integer, String> _page = new ConcurrentHashMap<>(); // page currently on screen (for delayed refreshes)
	private final Map<Integer, String> _simsView = new ConcurrentHashMap<>(); // friends / recruit / waiting
	private final Map<Integer, Integer> _craftSex = new ConcurrentHashMap<>(); // -1 random (default), 0 male, 1 female

	// ===================================================================================================
	// Who is a sim
	// ===================================================================================================

	private static boolean isPartySim(Player p)
	{
		return PhantomPartyManager.getInstance().isRecruit(p) || PhantomBuddyManager.getInstance().isBuddy(p);
	}

	private static boolean hasSims(Player owner)
	{
		final Party party = owner.getParty();
		if (party == null)
		{
			return false;
		}
		for (Player member : party.getMembers())
		{
			if ((member != owner) && isPartySim(member))
			{
				return true;
			}
		}
		return false;
	}

	private static Player findPartySim(Player owner, String name)
	{
		final Party party = owner.getParty();
		if ((party == null) || (name == null))
		{
			return null;
		}
		for (Player member : party.getMembers())
		{
			if ((member != owner) && member.getName().equalsIgnoreCase(name) && isPartySim(member))
			{
				return member;
			}
		}
		return null;
	}

	/** Your befriended sims (Luna and co) that are online right now. */
	private static List<Player> onlineFriends(Player owner)
	{
		final List<Player> result = new ArrayList<>();
		for (Integer id : owner.getFriendList())
		{
			final Player friend = World.getInstance().getPlayer(id.intValue());
			if ((friend != null) && PhantomManager.getInstance().isRegular(friend))
			{
				result.add(friend);
			}
		}
		return result;
	}

	/** Recruits and idle buddies standing near you that are waiting for an invite. */
	private static List<Player> waitingSims(Player owner)
	{
		final List<Player> result = new ArrayList<>();
		for (Player p : World.getInstance().getVisibleObjectsInRange(owner, Player.class, NEARBY_RANGE))
		{
			if ((p == owner) || p.isInParty() || p.isDead())
			{
				continue;
			}
			if (PhantomPartyManager.getInstance().isRecruit(p) || PhantomBuddyManager.getInstance().isBuddy(p))
			{
				result.add(p);
			}
		}
		return result;
	}

	// ===================================================================================================
	// Actions (each one tells you in the system chat what it just did)
	// ===================================================================================================

	void invite(Player owner, String name)
	{
		final Player target = (name == null) ? null : World.getInstance().getPlayer(name);
		if ((target == null) || (target == owner))
		{
			owner.sendMessage("[Sim] No sim named '" + name + "' is online.");
			return;
		}
		if (target.isInParty())
		{
			owner.sendMessage("[Sim] " + target.getName() + " is already in a party.");
			return;
		}
		final boolean joined;
		if (PhantomPartyManager.getInstance().isRecruit(target))
		{
			joined = PhantomPartyManager.getInstance().onInvited(owner, target);
		}
		else if (PhantomBuddyManager.getInstance().isBuddy(target))
		{
			joined = PhantomBuddyManager.getInstance().onInvited(owner, target);
		}
		else if (PhantomManager.getInstance().isRegular(target) && owner.getFriendList().contains(target.getObjectId()))
		{
			joined = PhantomPartyManager.getInstance().onInvitedFriend(owner, target);
		}
		else
		{
			owner.sendMessage("[Sim] " + target.getName() + " is not a sim you can invite from here.");
			return;
		}
		owner.sendMessage(joined ? ("[Sim] " + target.getName() + " joined your party.") : ("[Sim] Could not party " + target.getName() + " (party full or sim dead)."));
	}

	/** Dismisses a sim through its own "dismiss" command; it says goodbye and leaves ~2 seconds later, so the page refreshes after that. */
	void drop(Player owner, String name)
	{
		final Player sim = findPartySim(owner, name);
		if (sim == null)
		{
			owner.sendMessage("[Sim] No sim named '" + name + "' in your party.");
			return;
		}
		route(owner, sim, "dismiss");
		owner.sendMessage("[Sim] " + sim.getName() + " is leaving the party.");
		refreshLater(owner, "party", 2600);
	}

	/** Shouts a recruit request for one role - the sim spawns off-screen, walks over, and waits for your invite. */
	void find(Player owner, String token, String level)
	{
		final Recruit recruit;
		if ("dd".equals(token))
		{
			recruit = PhantomManager.rollDpsRecruit(null);
		}
		else
		{
			final PartyRole role = PartyRole.fromToken(token);
			// The spoiler line (Dwarven Fighter > Scavenger > Bounty Hunter > Fortune Seeker) exists only for dwarves. The
			// manager's default race for that role is Human, which has no such class, so without an explicit race the
			// recruit degrades to a plain human fighter.
			final Race race = (role == PartyRole.BOUNTY_HUNTER) ? Race.DWARF : null;
			recruit = (role == null) ? null : new Recruit(role, 0, race);
		}
		if (recruit == null)
		{
			owner.sendMessage("[Sim] Unknown role '" + token + "'.");
			return;
		}
		PhantomPartyManager.getInstance().recruitFromShout(owner, List.of(recruit), parseInt(level)); // 0 = around your level
		owner.sendMessage("[Sim] Looking for a " + token + " - it will appear under Sims > Recruit in a few seconds.");
		_simsView.put(owner.getObjectId(), "recruit");
		refreshLater(owner, "sims", 2000);
		refreshLater(owner, "sims", 4500);
		refreshLater(owner, "sims", 8000);
	}

	/** Creates a brand-new persistent friend at your level and befriends it, exactly like the friend orders do. */
	void craft(Player owner, String classSpec, String name)
	{
		if ((name == null) || name.isBlank())
		{
			owner.sendMessage("[Sim] Type the friend's name first.");
			return;
		}
		final int sex = _craftSex.getOrDefault(owner.getObjectId(), -1);
		ThreadPool.execute(() -> owner.sendMessage("[Sim] " + PhantomManager.getInstance().craftFriend(owner, name.trim(), classSpec, 0, sex, -1, -1, -1)));
		refreshLater(owner, "sims", 1500);
		refreshLater(owner, "sims", 3500);
	}

	/** Sets the sex (male/female/random) the next crafted friend uses; the Sims tab remembers your last choice. */
	void setCraftSex(Player owner, String value)
	{
		final int sex;
		switch (value)
		{
			case "m":
			{
				sex = 0;
				break;
			}
			case "f":
			{
				sex = 1;
				break;
			}
			default:
			{
				sex = -1;
				break;
			}
		}
		_craftSex.put(owner.getObjectId(), sex);
	}

	/**
	 * Pull 1/2/3: a "puller" sim leaves the camp, grabs that many mobs and runs them back so the party kills them at the
	 * camp. Uses the sim you already picked on the Party tab, or picks one for you (a tank first, else a damage dealer).
	 */
	void setPullSize(Player owner, String value)
	{
		final int id = owner.getObjectId();
		final int size = Math.max(1, Math.min(3, parseInt(value)));
		_pullSize.put(id, size);
		Player puller = null;
		final String pullerName = _puller.get(id);
		if (pullerName != null)
		{
			puller = findPartySim(owner, pullerName);
		}
		if ((puller == null) || !PhantomPartyManager.getInstance().isRecruit(puller))
		{
			puller = pickPuller(owner);
		}
		if (puller == null)
		{
			owner.sendMessage("[Sim] Nobody can pull: you need a recruited tank or damage dealer in the party.");
			return;
		}
		route(owner, puller, "pull " + size);
		_puller.put(id, puller.getName());
		_camp.put(id, true);
		owner.sendMessage("[Sim] Camp on. " + puller.getName() + " will fetch " + size + " mob" + ((size > 1) ? "s" : "") + " at a time and bring them here; the rest kill them at the camp.");
	}

	/** The best default puller: a recruited tank, else the first recruited damage dealer. */
	private static Player pickPuller(Player owner)
	{
		final Party party = owner.getParty();
		if (party == null)
		{
			return null;
		}
		Player fallback = null;
		for (Player member : party.getMembers())
		{
			if ((member == owner) || !PhantomPartyManager.getInstance().isRecruit(member))
			{
				continue;
			}
			final PartyRole role = PhantomManager.roleForClass(member.getPlayerClass());
			if (role == PartyRole.TANK)
			{
				return member;
			}
			if ((fallback == null) && !role.isSupport())
			{
				fallback = member;
			}
		}
		return fallback;
	}

	/** Makes one sim the camp's puller (planting the camp at your feet if there isn't one). */
	void pull(Player owner, String name)
	{
		final Player sim = findPartySim(owner, name);
		if ((sim == null) || !PhantomPartyManager.getInstance().isRecruit(sim))
		{
			owner.sendMessage("[Sim] Pick a recruited sim to pull.");
			return;
		}
		final int id = owner.getObjectId();
		int size = _pullSize.getOrDefault(id, 1);
		if (size < 1)
		{
			size = 1;
			_pullSize.put(id, 1);
		}
		route(owner, sim, "pull " + size);
		_puller.put(id, sim.getName());
		_camp.put(id, true);
		owner.sendMessage("[Sim] " + sim.getName() + " is the puller (" + size + " at a time). Camp planted where you stand.");
	}

	/** A party-wide order: the same line you would type in party chat. */
	void group(Player owner, String key)
	{
		final String text = groupText(key);
		if (text == null)
		{
			return;
		}
		if (!hasSims(owner))
		{
			owner.sendMessage("[Sim] You have no sims in your party.");
			return;
		}
		PhantomPartyManager.getInstance().handlePartyChat(owner, text);
		owner.sendMessage("[Sim] " + describe(key));

		final int id = owner.getObjectId();
		switch (key)
		{
			case "hold":
			case "assist":
			{
				_stance.put(id, key);
				_camp.put(id, false); // any movement/targeting order breaks the camp
				break;
			}
			case "follow":
			{
				_stance.put(id, "assist"); // regroup, then they carry on assisting you
				_camp.put(id, false);
				break;
			}
			case "free":
			{
				_stance.put(id, "farm");
				_camp.put(id, false);
				break;
			}
			case "camp":
			{
				_camp.put(id, true);
				break;
			}
			case "nocamp":
			{
				_camp.put(id, false);
				break;
			}
			case "nopull":
			{
				_pullSize.put(id, 0);
				_puller.remove(id);
				break;
			}
			case "holdfire":
			{
				_raidWait.put(id, true);
				break;
			}
			case "all":
			{
				_raidWait.put(id, false);
				break;
			}
			default:
			{
				break;
			}
		}
	}

	/** An order to one sim: the same line you would whisper to it. */
	void member(Player owner, String key, String name)
	{
		final Player sim = findPartySim(owner, name);
		final String text = memberText(key);
		if ((sim == null) || (text == null))
		{
			owner.sendMessage("[Sim] No sim named '" + name + "' in your party.");
			return;
		}
		route(owner, sim, text);
		owner.sendMessage("[Sim] " + sim.getName() + ": " + text + ".");
	}

	void setView(Player owner, String view)
	{
		_simsView.put(owner.getObjectId(), view);
	}

	private static void route(Player owner, Player sim, String text)
	{
		if (PhantomPartyManager.getInstance().isRecruit(sim))
		{
			PhantomPartyManager.getInstance().handleWhisper(owner, sim, text);
		}
		else if (PhantomBuddyManager.getInstance().isBuddy(sim))
		{
			PhantomBuddyManager.getInstance().handleWhisper(owner, sim, text);
		}
	}

	/** Re-draws a page after a delay, but only if that page is still the one on screen (never steals your view). */
	private void refreshLater(Player owner, String page, long delayMs)
	{
		ThreadPool.schedule(() ->
		{
			if (owner.isOnline() && page.equals(_page.get(owner.getObjectId())))
			{
				show(owner, page);
			}
		}, delayMs);
	}

	private static int parseInt(String value)
	{
		try
		{
			return Integer.parseInt(value.trim());
		}
		catch (Exception e)
		{
			return 0;
		}
	}

	private static String groupText(String key)
	{
		switch (key)
		{
			case "assist":
			{
				return "assist";
			}
			case "free":
			{
				return "attack freely";
			}
			case "follow":
			{
				return "follow";
			}
			case "hold":
			{
				return "stay";
			}
			case "camp":
			{
				return "camp here";
			}
			case "nocamp":
			{
				return "break camp";
			}
			case "nopull":
			{
				return "stop pulling";
			}
			case "tank":
			{
				return "tank attack";
			}
			case "all":
			{
				return "all attack";
			}
			case "holdfire":
			{
				return "hold fire";
			}
			case "rebuff":
			{
				return "buff all songs dance"; // buffers rebuff everyone; Swordsingers/Bladedancers sing/dance
			}
			case "buffme":
			{
				return "buff me songs dance"; // buffers rebuff you; singers/dancers refresh their music
			}
			case "songs":
			{
				return "songs dance";
			}
			case "heal":
			{
				return "heal me";
			}
			case "loot":
			{
				return "party return loot";
			}
			case "brb":
			{
				return "brb";
			}
			case "disbandall":
			{
				return "bye"; // every recruit/buddy leaves the party; a whole-party "disband" order
			}
			default:
			{
				return null;
			}
		}
	}

	private static String describe(String key)
	{
		switch (key)
		{
			case "assist":
			{
				return "Assist: sims fight the mob you target.";
			}
			case "free":
			{
				return "Farm: sims hunt on their own around you (they come back if they stray).";
			}
			case "follow":
			{
				return "Regroup: sims stop fighting and come back to you (then they keep assisting).";
			}
			case "hold":
			{
				return "Hold: sims stop following and stay where they are.";
			}
			case "camp":
			{
				return "Camp on: the party holds THIS spot and kills only what is brought to it. Use Pull 1/2/3 to send a puller for mobs.";
			}
			case "nocamp":
			{
				return "Camp off: the party is free again.";
			}
			case "nopull":
			{
				return "No pull: the puller stops fetching mobs (camp stays).";
			}
			case "tank":
			{
				return "Tank Attack: the tank pulls the raid boss; the rest join once it has aggro.";
			}
			case "all":
			{
				return "Raid: everyone attacks now (no waiting for the tank).";
			}
			case "holdfire":
			{
				return "Raid: sims wait for the tank to engage first.";
			}
			case "rebuff":
			{
				return "Buffers rebuff the whole party; Swordsingers and Bladedancers cast their songs and dances.";
			}
			case "buffme":
			{
				return "Buffers recast your full kit; Swordsingers and Bladedancers refresh their songs and dances.";
			}
			case "songs":
			{
				return "Swordsingers sing and Bladedancers dance now (a Bladedancer needs its dual swords equipped).";
			}
			case "heal":
			{
				return "Healers cast a heal on you.";
			}
			case "loot":
			{
				return "Looting sims hand you what they collected.";
			}
			case "brb":
			{
				return "BRB: sims wait for you (grace extended).";
			}
			case "disbandall":
			{
				return "Every sim says goodbye and leaves the party.";
			}
			default:
			{
				return key;
			}
		}
	}

	private static String memberText(String key)
	{
		switch (key)
		{
			case "assist":
			{
				return "assist";
			}
			case "free":
			{
				return "attack freely";
			}
			case "follow":
			{
				return "follow";
			}
			case "stay":
			{
				return "stay";
			}
			case "rebuff":
			{
				return "buff all";
			}
			case "heal":
			{
				return "heal me";
			}
			case "stand":
			{
				return "stand"; // interrupts an MP-rest sit
			}
			default:
			{
				return null;
			}
		}
	}

	// ===================================================================================================
	// Rendering helpers
	// ===================================================================================================

	private static String btn(String label, String action, boolean active)
	{
		return btnW(label, action, active, BTN_W);
	}

	private static String btn(String label, String action)
	{
		return btnW(label, action, false, BTN_W);
	}

	private static String btnW(String label, String action, boolean active, int width)
	{
		return "<button value=\"" + label + "\" action=\"bypass -h " + action + "\" width=" + width + " height=" + BTN_H + " back=\"" + BTN_ON + "\" fore=\"" + (active ? BTN_ON : BTN_OFF) + "\">";
	}

	/** A tab: a flat, frameless button, so it looks the same at any width and never gets a cropped border. */
	private static String tabBtn(String label, String action, boolean active, int width)
	{
		return "<button value=\"" + label + "\" action=\"bypass -h " + action + "\" width=" + width + " height=" + TAB_H + " back=\"" + TAB_ON + "\" fore=\"" + (active ? TAB_ON : TAB_OFF) + "\">";
	}

	private static String td(int width, String content)
	{
		return "<td" + ((width > 0) ? (" width=" + width) : "") + ">" + content + "</td>";
	}

	private static String tr(String... cells)
	{
		final StringBuilder sb = new StringBuilder("<table cellspacing=0 cellpadding=0><tr>");
		for (String c : cells)
		{
			sb.append(c);
		}
		return sb.append("</tr></table>").toString();
	}

	private static String cell(String content)
	{
		return "<td>" + content + "</td>";
	}

	private static String head(String text)
	{
		return "<font color=\"LEVEL\">" + text + "</font>";
	}

	private static String classLabel(Player p)
	{
		final String[] words = p.getPlayerClass().name().toLowerCase().split("_");
		final StringBuilder sb = new StringBuilder();
		for (String word : words)
		{
			if (word.isEmpty())
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(' ');
			}
			sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return sb.toString();
	}

	// ===================================================================================================
	// Pages
	// ===================================================================================================

	void show(Player player, String page)
	{
		_page.put(player.getObjectId(), page);
		final StringBuilder sb = new StringBuilder(4000);

		// ---- fixed header: identical on every page, so the tabs never move ----
		sb.append("<html><body><center>").append(head("Sim-Management")).append("<table cellspacing=0 cellpadding=0><tr>");
		sb.append(cell(tabBtn("Home", "sim_home", page.equals("home") || page.equals("help"), TAB_W)));
		sb.append(cell(tabBtn("Party", "sim_party", page.equals("party"), TAB_W)));
		sb.append(cell(tabBtn("Targets", "sim_targets", page.equals("targets"), TAB_W)));
		sb.append(cell(tabBtn("Sims", "sim_sims", page.equals("sims"), TAB_W)));
		sb.append("</tr></table></center>");
		sb.append("<img src=\"L2UI.SquareGray\" width=248 height=1><br1>");

		// ---- page body (always short enough to fit without a scrollbar) ----
		switch (page)
		{
			case "party":
			{
				partyPage(player, sb);
				break;
			}
			case "targets":
			{
				targetsPage(player, sb);
				break;
			}
			case "sims":
			{
				simsPage(player, sb);
				break;
			}
			case "help":
			{
				helpPage(sb);
				break;
			}
			default:
			{
				homePage(player, sb);
				break;
			}
		}

		sb.append("</body></html>");
		String html = sb.toString();
		if (html.length() > MAX_HTML)
		{
			html = html.substring(0, MAX_HTML) + "</body></html>"; // crude guard so the window never overflows
		}
		final NpcHtmlMessage message = new NpcHtmlMessage();
		message.setHtml(html);
		player.sendPacket(message);
	}

	private void homePage(Player p, StringBuilder sb)
	{
		final int id = p.getObjectId();
		final String stance = _stance.getOrDefault(id, "assist"); // sims join in assist mode
		final boolean camp = _camp.getOrDefault(id, false);
		final int pull = _pullSize.getOrDefault(id, 1);
		final String puller = _puller.get(id);

		sb.append("<center>").append(head("Party Stance:")).append("<br1><table><tr>");
		sb.append(cell(btn("Hold", "sim_do hold home", stance.equals("hold"))));
		sb.append(cell(btn("Assist", "sim_do assist home", stance.equals("assist"))));
		sb.append(cell(btn("Farm", "sim_do free home", stance.equals("farm"))));
		sb.append("</tr></table>");

		sb.append(head("Camp:")).append("<br1><table><tr>");
		sb.append(cell(camp ? btn("Camp On", "sim_do nocamp home", true) : btn("Camp Off", "sim_do camp home", false)));
		sb.append(cell(btn("Stop Pull", "sim_do nopull home", pull == 0)));
		sb.append("</tr></table>");

		sb.append(head("Pull (mobs per run):")).append(" ").append(((puller != null) && (pull > 0)) ? (puller + " x" + pull) : "none").append("<br1><table><tr>");
		sb.append(cell(btn("Pull 1", "sim_size 1", pull == 1)));
		sb.append(cell(btn("Pull 2", "sim_size 2", pull == 2)));
		sb.append(cell(btn("Pull 3", "sim_size 3", pull == 3)));
		sb.append("</tr></table>");

		sb.append(head("Party Control:")).append("<br1><table><tr>");
		sb.append(cell(btn("Members", "sim_party")));
		sb.append(cell(btn("Raid", "sim_targets")));
		sb.append(cell(btn("BRB", "sim_do brb home")));
		sb.append("</tr><tr>");
		sb.append(cell(btn("Disband All", "sim_do disbandall home")));
		sb.append("</tr></table>");

		sb.append(head("Sim Commands:")).append("<br1><table><tr>");
		sb.append(cell(btn("Buff Me", "sim_do buffme home")));
		sb.append(cell(btn("Recruit", "sim_sims")));
		sb.append(cell(btn("Loot", "sim_do loot home")));
		sb.append("</tr><tr>");
		sb.append(cell(btn("Regroup", "sim_do follow home")));
		sb.append(cell(btn("Songs", "sim_do songs home")));
		sb.append(cell(btn("Help", "sim_help")));
		sb.append("</tr></table></center>");
	}

	/**
	 * Every party member in one list, no paging: when it runs longer than the window, the client's own vertical
	 * scrollbar appears on the right (the same one the Friends/Recruit tabs already show), so nothing is hidden.
	 */
	private void partyPage(Player p, StringBuilder sb)
	{
		final Party party = p.getParty();
		final List<Player> others = new ArrayList<>();
		if (party != null)
		{
			for (Player member : party.getMembers())
			{
				if (member != p)
				{
					others.add(member);
				}
			}
		}
		if (others.isEmpty())
		{
			sb.append("You are not in a party.<br1>Use the Sims tab to recruit or invite a friend.");
			return;
		}

		sb.append(head("Members (" + others.size() + "):")).append("<br1>");
		for (Player member : others)
		{
			final PartyRole role = PhantomManager.roleForClass(member.getPlayerClass());
			final boolean sim = isPartySim(member);
			final boolean recruit = PhantomPartyManager.getInstance().isRecruit(member);
			final boolean isPuller = member.getName().equals(_puller.get(p.getObjectId()));
			final String info = member.getName() + " " + role.name() + " " + member.getLevel() + " " + member.getCurrentHpPercent() + "%";

			sb.append(tr(td(172, info), td(68, sim ? btn("Drop", "sim_drop " + member.getName()) : "")));
			if (sim)
			{
				if (recruit)
				{
					sb.append(role.isSupport() //
						? tr(td(68, btn("Assist", "sim_mem assist " + member.getName())), td(68, btn("Free", "sim_mem free " + member.getName()))) //
						: tr(td(68, btn("Assist", "sim_mem assist " + member.getName())), td(68, btn("Free", "sim_mem free " + member.getName())), td(68, btn("Pull", "sim_pull " + member.getName(), isPuller))));
				}
				if (role.isSupport())
				{
					sb.append(tr(td(68, btn("Buff", "sim_mem rebuff " + member.getName())), td(68, btn("Heal", "sim_mem heal " + member.getName())), td(68, btn("Stand", "sim_mem stand " + member.getName()))));
				}
			}
			sb.append("<br1>");
		}
	}

	private void targetsPage(Player p, StringBuilder sb)
	{
		final int id = p.getObjectId();
		final boolean wait = _raidWait.getOrDefault(id, true);
		final WorldObject target = p.getTarget();
		if (target instanceof Creature)
		{
			final Creature creature = (Creature) target;
			sb.append(head("Your target:")).append(" ").append(creature.getName()).append(" Lv ").append(creature.getLevel()).append(" (HP ").append(creature.getCurrentHpPercent()).append("%)<br1>");
		}
		else
		{
			sb.append(head("Your target:")).append(" none<br1>");
		}
		sb.append("Sims in Assist or Farm mode fight what you target.<br>");

		sb.append("<center>").append(head("Wait for the tank to engage a raid:")).append("<br1><table><tr>");
		sb.append(cell(btn("On", "sim_do holdfire targets", wait)));
		sb.append(cell(btn("Off", "sim_do all targets", !wait)));
		sb.append("</tr></table>");

		sb.append(head("Raid Orders:")).append("<br1><table><tr>");
		sb.append(cell(btn("Tank Attack", "sim_do tank targets")));
		sb.append(cell(btn("Rebuff All", "sim_do rebuff targets")));
		sb.append(cell(btn("Heal Me", "sim_do heal targets")));
		sb.append("</tr><tr>");
		sb.append(cell(btn("Songs", "sim_do songs targets")));
		sb.append(cell(btn("Refresh", "sim_targets")));
		sb.append("</tr></table></center>");
	}

	private void simsPage(Player p, StringBuilder sb)
	{
		final String view = _simsView.getOrDefault(p.getObjectId(), "friends");
		sb.append("<center><table cellspacing=0 cellpadding=0><tr>");
		sb.append(cell(tabBtn("Friends", "sim_view friends", view.equals("friends"), 124)));
		sb.append(cell(tabBtn("Recruit", "sim_view recruit", view.equals("recruit"), 124)));
		sb.append("</tr></table></center>");

		switch (view)
		{
			case "recruit":
			{
				sb.append(head("Recruit (like an LFM shout)")).append("<br1>Level (empty = yours): <edit var=\"lvl\" width=40 height=15><br1><table><tr>");
				sb.append(cell(btn("Tank", "sim_find tank $lvl")));
				sb.append(cell(btn("Healer", "sim_find healer $lvl")));
				sb.append(cell(btn("Buffer", "sim_find buffer $lvl")));
				sb.append("</tr><tr>");
				sb.append(cell(btn("DD", "sim_find dd $lvl")));
				sb.append(cell(btn("Nuker", "sim_find nuker $lvl")));
				sb.append(cell(btn("Archer", "sim_find archer $lvl")));
				sb.append("</tr><tr>");
				sb.append(cell(btn("Dagger", "sim_find dagger $lvl")));
				sb.append(cell(btn("Melee", "sim_find melee $lvl")));
				sb.append(cell(btn("Singer", "sim_find singer $lvl")));
				sb.append("</tr><tr>");
				sb.append(cell(btn("Dancer", "sim_find dancer $lvl")));
				sb.append(cell(btn("Spoiler", "sim_find spoiler $lvl")));
				sb.append("</tr></table><br>");
				waitingSection(p, sb, "recruit");
				break;
			}
			default:
			{
				final List<Player> friends = onlineFriends(p);
				sb.append(head("Friends online (" + friends.size() + "):")).append("<br1>");
				int shown = 0;
				for (Player friend : friends)
				{
					if (shown++ >= 12)
					{
						break;
					}
					sb.append(tr(td(176, friend.getName() + " " + classLabel(friend) + " " + friend.getLevel()), td(68, friend.isInParty() ? btn("In party", "sim_view friends", true) : btn("Invite", "sim_invite " + friend.getName()))));
				}
				if (friends.isEmpty())
				{
					sb.append("No friends online yet.<br1>");
				}
				final int sex = _craftSex.getOrDefault(p.getObjectId(), -1);
				sb.append(head("New friend (your level):")).append("<br1><table><tr>");
				sb.append(cell(btn("Male", "sim_sex m", sex == 0)));
				sb.append(cell(btn("Female", "sim_sex f", sex == 1)));
				sb.append(cell(btn("Random", "sim_sex r", sex == -1)));
				sb.append("</tr></table><br1>Name: <edit var=\"fname\" width=110 height=15><br1><table><tr>");
				sb.append(cell(btn("Fighter", "sim_craft fighter $fname")));
				sb.append(cell(btn("Mage", "sim_craft mage $fname")));
				sb.append(cell(btn("Elder", "sim_craft elder $fname")));
				sb.append("</tr><tr>");
				sb.append(cell(btn("Prophet", "sim_craft prophet $fname")));
				sb.append(cell(btn("Warcryer", "sim_craft warcryer $fname")));
				sb.append("</tr></table><br>");
				waitingSection(p, sb, "friends");
				break;
			}
		}
	}

	/** The "waiting for your invite" list, shown under both Friends and Recruit (there's no separate tab for it). */
	private void waitingSection(Player p, StringBuilder sb, String refreshView)
	{
		final List<Player> waiting = waitingSims(p);
		sb.append(head("Waiting for your invite (" + waiting.size() + "):")).append("<br1>");
		int shown = 0;
		for (Player sim : waiting)
		{
			if (shown++ >= 20) // just a sane HTML-size cap; the window's own scrollbar handles the rest
			{
				break;
			}
			sb.append(tr(td(176, sim.getName() + " " + classLabel(sim) + " " + sim.getLevel()), td(68, btn("Invite", "sim_invite " + sim.getName()))));
		}
		if (waiting.isEmpty())
		{
			sb.append("Nobody yet. Use Recruit, then wait a few seconds.<br1>");
		}
		sb.append("<table><tr>").append(cell(btn("Refresh", "sim_view " + refreshView))).append("</tr></table>");
	}

	private void helpPage(StringBuilder sb)
	{
		sb.append(head("Party chat / whisper:")).append("<br1>");
		sb.append("assist, attack freely, follow, hold, gather<br1>");