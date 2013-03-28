package ru.spbstu.telematics.flowgen.openflow.datapath;


import org.json.JSONObject;
import ru.spbstu.telematics.flowgen.openflow.rules.Command;
import ru.spbstu.telematics.flowgen.openflow.rules.IFirewallRule;
import ru.spbstu.telematics.flowgen.openflow.rules.OnePortFirewallBroadcastRule;
import ru.spbstu.telematics.flowgen.openflow.rules.OnePortFirewallGatewayRule;
import ru.spbstu.telematics.flowgen.openflow.rules.OnePortFirewallSubnetRule;
import ru.spbstu.telematics.flowgen.openflow.rules.OnePortFirewallVmRule;
import ru.spbstu.telematics.flowgen.utils.OpenflowUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class Datapath implements IDatapath {

	private static final String NOT_INITIALIZED = "<not_initialized>";

	// Datapath params
	private String dpid = NOT_INITIALIZED;
	private String name = NOT_INITIALIZED;
	private int firewallPort = OpenflowUtils.DEFAULT_PORT;

	// Network params
	private boolean connectedToNetwork = false;
	private String gatewayMac = null;
	private int trunkPort = OpenflowUtils.DEFAULT_PORT;

	// VM
	private Map<String, Integer> macPortMap;
	private Map<Integer, Integer> portVmNumberMap;

	// Listeners
	private LinkedList<IDatapathListener> listeners;


	/**
	 * Constructors
	 */

	public Datapath(String dpid, String name, int trunkPort, int firewallPort, String gatewayMac) {
		setDpid(dpid);
		setName(name);
		setTrunkPort(trunkPort);
		setFirewallPort(firewallPort);
		setGatewayMac(gatewayMac);
		macPortMap = new HashMap<String, Integer>();
		portVmNumberMap = new HashMap<Integer, Integer>();
		listeners = new LinkedList<IDatapathListener>();
	}


	/**
	 * DPID
	 */

	@Override
	public String getDpid() {
		return dpid;
	}

	private void setDpid(String dpid) {
		if (!OpenflowUtils.validateDpid(dpid)) {
			throw new IllegalArgumentException("Wrong DPID (" + dpid + ") of datapath with mane " + name);
		}
		this.dpid = dpid.toLowerCase();
	}


	/**
	 * Name
	 */

	@Override
	public String getName() {
		return name;
	}

	private void setName(String name) {
		if (!OpenflowUtils.validateDatapathName(name)) {
			throw new IllegalArgumentException("Wrong name (" + name + ") of datapath with ID " + dpid);
		}
		this.name = name;
	}


	/**
	 * Trunk port
	 */

	@Override
	public int getTrunkPort() {
		return trunkPort;
	}

	private void setTrunkPort(int trunkPort) {
		if (!OpenflowUtils.validatePortNumber(trunkPort)) {
			throw new IllegalArgumentException("Wrong trunk port (" + trunkPort + ") in datapath " + toString());
		}
		if (trunkPort == firewallPort) {
			throw new IllegalArgumentException("New trunk port equals to firewall port (" + firewallPort + ") of datapath " + toString());
		}
		this.trunkPort = trunkPort;
	}


	/**
	 * Firewall port
	 */

	@Override
	public int getFirewallPort() {
		return firewallPort;
	}

	private void setFirewallPort(int firewallPort) {
		if (!OpenflowUtils.validatePortNumber(firewallPort)) {
			throw new IllegalArgumentException("Wrong firewall port (" + firewallPort + ") in datapath " + toString());
		}
		if (firewallPort == trunkPort) {
			throw new IllegalArgumentException("New firewall port equals to trunk port (" + trunkPort + ") of datapath " + toString());
		}
		this.firewallPort = firewallPort;
	}


	/**
	 * Gateway MAC
	 */

	@Override
	public String getGatewayMac() {
		return gatewayMac;
	}

	@Override
	public boolean isGatewayMac(String mac) {
		return gatewayMac.equalsIgnoreCase(mac);
	}

	private void setGatewayMac(String mac) {
		if (!OpenflowUtils.validateMac(mac)) {
			throw new IllegalArgumentException("Wrong gateway MAC (" + mac + ") in datapath " + toString());
		}
		gatewayMac = mac.toLowerCase();
	}


	/**
	 * VM
	 */

	@Override
	public boolean containsVm(String mac) {
		return macPortMap.containsKey(mac.toLowerCase());
	}

	@Override
	public int getVmPort(String mac) {
		int result;

		if (containsVm(mac)) {
			result = macPortMap.get(mac.toLowerCase());
		} else {
			result = OpenflowUtils.DEFAULT_PORT;
		}

		return result;
	}

	@Override
	public Map<String, Integer> getMacPortMap() {
		return new HashMap<String, Integer>(macPortMap);
	}


	/**
	 * IDatapath implementation
	 */

	@Override
	public synchronized void connectVm(String mac, int port) {

		if (mac == null) {
			throw new IllegalArgumentException("VM MAC is null in datapath " + toString());
		}
		if (!OpenflowUtils.validateMac(mac)) {
			throw new IllegalArgumentException("Wrong VM MAC (" + mac + ") in datapath " + toString());
		}
		if (isGatewayMac(mac)) {
			throw new IllegalArgumentException("New VM MAC equals to gateway MAC (" + gatewayMac + ") of datapath " + toString());
		}
		if (containsVm(mac)) {
			throw new IllegalArgumentException("VM with such MAC (" + mac + ") already connected to port " +
					macPortMap.get(mac.toLowerCase()) + " of datapath " + toString());
		}

		if (!OpenflowUtils.validatePortNumber(port)) {
			throw new IllegalArgumentException("Wrong port number (" + port + ") to VM with MAC " + mac + " in datapath " + toString());
		}
		if (port == trunkPort) {
			throw new IllegalArgumentException("New VM port equals to trunk port (" + trunkPort + ") of datapath " + toString());
		}
		if (port == firewallPort) {
			throw new IllegalArgumentException("New VM port equals to firewall port (" + firewallPort + ") of datapath " + toString());
		}

		Command.Type commandType;
		if (isPortEmpty(port)) {
			commandType = Command.Type.FLOW_ADD_FIRST_VM;
		} else {
			commandType = Command.Type.FLOW_ADD_ANOTHER_VM;
		}
		mac = mac.toLowerCase();
		macPortMap.put(mac, port);
		incrementNumberOfVmsConnectedToPort(port);
		IFirewallRule rule = getVmRule(mac);
		JSONObject[] commands = createCommands(rule, commandType);
		notifyListeners(commands, Command.Action.FLOW_ADD);
	}

	@Override
	public synchronized void disconnectVm(String mac) {

		if(!containsVm(mac)) {
			throw new IllegalArgumentException("VM with such MAC (" + mac + ") not connected to datapath " + toString());
		}

		int port = getVmPort(mac);
		Command.Type commandType;
		if (isLastVmConnectedToPort(port)) {
			commandType = Command.Type.FLOW_REMOVE_LAST_VM;
		} else {
			commandType = Command.Type.FLOW_REMOVE_ANOTHER_VM;
		}
		mac = mac.toLowerCase();
		IFirewallRule rule = getVmRule(mac);
		macPortMap.remove(mac);
		decrementNumberOfVmsConnectedToPort(port);
		JSONObject[] commands = createCommands(rule, commandType);
		notifyListeners(commands, Command.Action.FLOW_REMOVE);
	}

	@Override
	public IFirewallRule getVmRule(String mac) {
		mac = mac.toLowerCase();
		if (containsVm(mac)) {
			return new OnePortFirewallVmRule(dpid, firewallPort, macPortMap.get(mac), mac);
		}
		return null;
	}

	@Override
	public List<IFirewallRule> getAllVmRules() {
		ArrayList<IFirewallRule> rules = new ArrayList<IFirewallRule>();
		Set<String> macs = macPortMap.keySet();
		for (String mac : macs) {
			rules.add(getVmRule(mac));
		}
		return rules;
	}

	// Network

	private void connectGateway() {
		JSONObject[] commands = createCommands(getGatewayRule(), Command.Type.FLOW_ADD_GATEWAY);
		notifyListeners(commands, Command.Action.FLOW_ADD);
	}

	private void disconnectGateway() {
		JSONObject[] commands = createCommands(getGatewayRule(), Command.Type.FLOW_REMOVE_GATEWAY);
		notifyListeners(commands, Command.Action.FLOW_REMOVE);
	}

	private IFirewallRule getGatewayRule() {
		return new OnePortFirewallGatewayRule(dpid, firewallPort, trunkPort, gatewayMac);
	}

	private void connectBroadcast() {
		JSONObject[] commands = createCommands(getBroadcastRule(), Command.Type.FLOW_ADD_BROADCAST);
		notifyListeners(commands, Command.Action.FLOW_ADD);
	}

	private void disconnectBroadcast() {
		JSONObject[] commands = createCommands(getBroadcastRule(), Command.Type.FLOW_REMOVE_BROADCAST);
		notifyListeners(commands, Command.Action.FLOW_REMOVE);
	}

	private IFirewallRule getBroadcastRule() {
		return new OnePortFirewallBroadcastRule(dpid, firewallPort);
	}

	private void connectSubnet() {
		JSONObject[] commands = createCommands(getSubnetRule(), Command.Type.FLOW_ADD_SUBNET);
		notifyListeners(commands, Command.Action.FLOW_ADD);
	}

	private void disconnectSubnet() {
		JSONObject[] commands = createCommands(getSubnetRule(), Command.Type.FLOW_REMOVE_SUBNET);
		notifyListeners(commands, Command.Action.FLOW_REMOVE);
	}

	private IFirewallRule getSubnetRule() {
		return new OnePortFirewallSubnetRule(dpid, firewallPort, trunkPort);
	}

	@Override
	public synchronized void connectToNetwork() {
		connectGateway();
		connectBroadcast();
		connectSubnet();
		connectedToNetwork = true;
	}

	@Override
	public synchronized void disconnectFromNetwork() {
		connectedToNetwork = false;
		disconnectSubnet();
		disconnectBroadcast();
		disconnectGateway();
	}

	@Override
	public boolean isConnectedToNetwork() {
		return connectedToNetwork;
	}

	@Override
	public List<IFirewallRule> getAllNetworkRules() {
		List<IFirewallRule> rules = new ArrayList<IFirewallRule>();
		rules.add(getGatewayRule());
		rules.add(getBroadcastRule());
		rules.add(getSubnetRule());
		return rules;

	}

	@Override
	public List<IFirewallRule> getAllRules() {
		List<IFirewallRule> rules = getAllNetworkRules();
		List<IFirewallRule> vmRules = getAllVmRules();
		rules.addAll(vmRules);
		return rules;
	}

	// VM Migration

	@Override
	public void migrateVm(String vmMac, IDatapath dstDatapath, int dstPort) {
		// TODO implement safe migration
		if (containsVm(vmMac)) {
			dstDatapath.connectVm(vmMac, dstPort);
			disconnectVm(vmMac);
		} else {
			throw new IllegalArgumentException("VM with such MAC (" + vmMac + ") not connected to datapath " + toString());
		}
	}

	// Listeners

	@Override
	public synchronized void registerListener(IDatapathListener listener) {
		if (!listeners.contains(listener)) {
			listeners.add(listener);
		}
	}

	@Override
	public synchronized void unregisterListener(IDatapathListener listener) {
		listeners.remove(listener);
	}

	private void notifyListeners(JSONObject[] commands, Command.Action action) {
		if (listeners != null && !listeners.isEmpty()) {
			switch (action) {
				case FLOW_ADD:
					for (IDatapathListener listener : listeners) {
						listener.onConnection(commands);
					}
					break;
				case FLOW_REMOVE:
					for (IDatapathListener listener : listeners) {
						listener.onDisconnection(commands);
					}
					break;
				default:
					throw new IllegalArgumentException("Unknown command action type " + action);
			}
		}
	}


	/**
	 * Other
	 */

	private int numberOfVmsConnectedToPort(int port) {
		if (!OpenflowUtils.validatePortNumber(port)) {
			throw new IllegalArgumentException("Wrong port number (" + port + ") checked in datapath " + toString());
		}
		if (port == trunkPort) {
			throw new IllegalArgumentException("Checked VM port equals to trunk port (" + trunkPort + ") of datapath " + toString());
		}
		if (port == firewallPort) {
			throw new IllegalArgumentException("Checked VM port equals to firewall port (" + firewallPort + ") of datapath " + toString());
		}

		int result = 0;
		if (portVmNumberMap.containsKey(port)) {
			result = portVmNumberMap.get(port);
		}
		return result;
	}

	private int incrementNumberOfVmsConnectedToPort(int port) {
		int number = numberOfVmsConnectedToPort(port);
		number++;
		portVmNumberMap.put(port, number);
		return number;
	}

	private int decrementNumberOfVmsConnectedToPort(int port) {
		int number = numberOfVmsConnectedToPort(port);
		if (number == 0) {
			throw new IllegalArgumentException("Port " + port + " of datapath " + toString() + " already empty");
		} else {
			number--;
		}
		portVmNumberMap.put(port, number);
		return number;
	}

	private boolean isPortEmpty(int port) {
		return numberOfVmsConnectedToPort(port) == 0;
	}

	private boolean isLastVmConnectedToPort(int port) {
		return numberOfVmsConnectedToPort(port) == 1;
	}


	private static JSONObject[] createCommands(IFirewallRule rule, Command.Type commandType) {

		Command.RuleGroup ruleGroup = Command.getRuleGroup(commandType);

		// For BROADCAST or SUBNET rule only OUT flow needed.
		// If new VM is connected to port which had one or more other connected VM or
		// if VM is disconnected from port which had one or more other connected VM only OUT flow needed.
		// For other cases need to create 2 flows: IN and OUT.

		boolean anotherVm = commandType == Command.Type.FLOW_ADD_ANOTHER_VM ||
				commandType == Command.Type.FLOW_REMOVE_ANOTHER_VM;

		boolean onlyOutFlow = ruleGroup == Command.RuleGroup.RULE_BROADCAST ||
				ruleGroup == Command.RuleGroup.RULE_SUBNET ||
				(ruleGroup == Command.RuleGroup.RULE_VM && anotherVm);

		JSONObject[] commands;
		Command.Action action = Command.getAction(commandType);

		if (onlyOutFlow) {

			commands = new JSONObject[1];
			if (action == Command.Action.FLOW_ADD) {
				commands[0] = rule.ovsOutFlowAddCommand();
			} else if (action == Command.Action.FLOW_REMOVE) {
				commands[0] = rule.ovsOutFlowRemoveCommand();
			} else {
				throw new IllegalArgumentException("Unknown command action of " + commandType);
			}

		} else {

			commands = new JSONObject[2];
			if (action == Command.Action.FLOW_ADD) {
				commands[0] = rule.ovsInFlowAddCommand();
				commands[1] = rule.ovsOutFlowAddCommand();
			} else if (action == Command.Action.FLOW_REMOVE) {
				commands[0] = rule.ovsInFlowRemoveCommand();
				commands[1] = rule.ovsOutFlowRemoveCommand();
			} else {
				throw new IllegalArgumentException("Unknown command action of " + commandType);
			}

		}

		return commands;
	}

	@Override
	public String toString() {
		return name + " (" + dpid + ")";
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}

		if (o == null || getClass() != o.getClass()) {
			return false;
		}

		Datapath datapath = (Datapath) o;

		return dpid.equals(datapath.dpid);

	}

	@Override
	public int hashCode() {
		return dpid.hashCode();
	}
}
