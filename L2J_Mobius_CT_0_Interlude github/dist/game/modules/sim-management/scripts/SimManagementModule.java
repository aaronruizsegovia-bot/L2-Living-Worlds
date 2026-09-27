package modules.simmanagement;

import org.l2jmobius.gameserver.handler.IBypassHandler;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.modules.GameModule;
import org.l2jmobius.gameserver.modules.ModuleContext;

public final class SimManagementModule implements GameModule
{
	@Override
	public void onEnable(ModuleContext context)
	{
		if (!context.config().getBoolean("Enabled", false))
		{
			return; // Switch off: register nothing, behave as stock.
		}
		final SimPanel panel = new SimPanel();
		context.handlers().registerVoicedCommand(new SimVoicedCommand(panel));
		context.handlers().registerBypass(new SimBypass(panel));
	}
}

/** `.sim` in chat (or a macro) opens the window. */
final class SimVoicedCommand implements IVoicedCommandHandler
{
	private final SimPanel _panel;

	SimVoicedCommand(SimPanel panel)
	{
		_panel = panel;
	}

	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		_panel.show(player, "home");
		return true;
	}

	@Override
	public String[] getCommandList()
	{
		return new String[]
		{
			"sim"
		};
	}
}

/** Every button in the window is a `bypass -h sim_...` that lands here. */
final class SimBypass implements IBypassHandler
{
	private static final String[] COMMANDS =
	{
		"sim_home",
		"sim_party",
		"sim_targets",
		"sim_sims",
		"sim_help",
		"sim_invite",
		"sim_drop",
		"sim_do",
		"sim_mem",
		"sim_pull",
		"sim_size",
		"sim_find",
		"sim_craft",
		"sim_sex",
		"sim_view"
	};
	private final SimPanel _panel;

	SimBypass(SimPanel panel)
	{
		_panel = panel;
	}

	@Override
	public boolean onCommand(String command, Player player, Creature bypassOrigin)
	{
		final String[] a = command.trim().split("\\s+");
		final String cmd = a[0].toLowerCase();
		String page = cmd.substring("sim_".length()); // home / party / targets / sims / help
		switch (cmd)
		{
			case "sim_invite":
			{
				if (a.length > 1)
				{
					_panel.invite(player, a[1]);
				}
				else
				{
					player.sendMessage("Type a name first.");
				}
				page = "party";
				break;
			}
			case "sim_drop":
			{
				if (a.length > 1)
				{
					_panel.drop(player, a[1]);
				}
				else
				{
					player.sendMessage("Type a name first.");
				}
				page = "party";
				break;
			}
			case "sim_do":
			{
				if (a.length > 1)
				{
					_panel.group(player, a[1]);
				}
				page = (a.length > 2) ? a[2] : "home";
				break;
			}
			case "sim_mem":
			{
				if (a.length > 2)
				{
					_panel.member(player, a[1], a[2]);
				}
				page = "party";
				break;
			}
			case "sim_pull":
			{
				if (a.length > 1)
				{
					_panel.pull(player, a[1]);
				}
				page = "party";
				break;
			}
			case "sim_size":
			{
				if (a.length > 1)
				{
					_panel.setPullSize(player, a[1]);
				}
				page = "home";
				break;
			}
			case "sim_find":
			{
				if (a.length > 1)
				{
					_panel.find(player, a[1], (a.length > 2) ? a[2] : "");
				}
				page = "sims";
				break;
			}
			case "sim_craft":
			{
				_panel.craft(player, (a.length > 1) ? a[1] : "fighter", (a.length > 2) ? a[2] : null);
				page = "sims";
				break;
			}
			case "sim_sex":
			{
				if (a.length > 1)
				{
					_panel.setCraftSex(player, a[1]);
				}
				page = "sims";
				break;
			}
			case "sim_view":
			{
				if (a.length > 1)
				{
					_panel.setView(player, a[1]);
				}
				page = "sims";
				break;
			}
			default:
			{
				break; // plain page switch
			}
		}
		_panel.show(player, page);
		return true;
	}

	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
}