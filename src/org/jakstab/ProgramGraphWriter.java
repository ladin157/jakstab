/*
 * ProgramGraphWriter.java - This file is part of the Jakstab project.
 * Copyright 2007-2012 Johannes Kinder <jk@jakstab.org>
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, see <http://www.gnu.org/licenses/>.
 */
package org.jakstab;

import java.awt.Color;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

import org.jakstab.analysis.*;
import org.jakstab.asm.AbsoluteAddress;
import org.jakstab.asm.BranchInstruction;
import org.jakstab.asm.Instruction;
import org.jakstab.asm.ReturnInstruction;
import org.jakstab.asm.SymbolFinder;
import org.jakstab.cfa.CFAEdge;
import org.jakstab.cfa.CFAEdge.Kind;
import org.jakstab.cfa.ControlFlowGraph;
import org.jakstab.cfa.RTLLabel;
import org.jakstab.cfa.VpcLiftedCFG;
import org.jakstab.cfa.VpcLocation;
import org.jakstab.rtl.expressions.ExpressionFactory;
import org.jakstab.rtl.statements.BasicBlock;
import org.jakstab.rtl.statements.RTLGoto;
import org.jakstab.rtl.statements.RTLHalt;
import org.jakstab.rtl.statements.RTLStatement;
import org.jakstab.util.*;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;

/**
 * Writes various graphs from a program structure.
 * 
 * @author Johannes Kinder
 */
public class ProgramGraphWriter {

	@SuppressWarnings("unused")
	private static final Logger logger = Logger.getLogger(ProgramGraphWriter.class);
	private Program program;
	
	private Set<RTLLabel> mustLeaves;
	private Set<RTLLabel> locations;
	private SetMultimap<RTLLabel, CFAEdge> inEdges;
	private SetMultimap<RTLLabel, CFAEdge> outEdges;
	private VpcLiftedCFG vcfg;

	public ProgramGraphWriter(Program program) {
		this.program = program;

		// TODO: Make functions in this class use these pre-initialized data structures 
		
		locations = new HashSet<RTLLabel>();
		mustLeaves = new HashSet<RTLLabel>();
		inEdges = HashMultimap.create();
		outEdges = HashMultimap.create();
		
		for (CFAEdge e : program.getCFA()) {
			inEdges.put(e.getTarget(), e);
			outEdges.put(e.getSource(), e);
			locations.add(e.getSource());
			locations.add(e.getTarget());
		}
		
		// Find locations which have an incoming MUST edge, but no outgoing one
		for (RTLLabel l : locations) {
			boolean foundMust = false;
			for (CFAEdge e : inEdges.get(l)) {
				foundMust |= e.getKind() == Kind.MUST;
			}
			
			if (!foundMust) 
				continue;
			
			foundMust = false;
			for (CFAEdge e : outEdges.get(l)) {
				foundMust |= e.getKind() == Kind.MUST;
			}
			
			if (!foundMust) {
				mustLeaves.add(l);
			}
			
		}
		
		if (!mustLeaves.isEmpty())
			logger.debug("Leaves of MUST-analysis: " + mustLeaves);
		
	}
	
	private GraphWriter createGraphWriter(String filename) {
		try {
			if (Options.graphML.getValue()) {
				return new GraphMLWriter(filename);
			} else {
				return new GraphvizWriter(filename);
			}
		} catch (IOException e) {
			logger.error("Cannot open output file!", e);
			return null;
		}
	}

	private Map<String,String> getNodeProperties(RTLLabel loc) {
		RTLStatement curStmt = program.getStatement(loc);
		Map<String,String> properties = new HashMap<String, String>();

		if (curStmt != null) {
			if (curStmt.getLabel().getAddress().getValue() >= 0xFACE0000L) {
				properties.put("color", "lightgrey");
				properties.put("fillcolor", "lightgrey");
			}

			if (program.getUnresolvedBranches().contains(curStmt.getLabel())) {
				properties.put("fillcolor", "red");
			}
			
			if (mustLeaves.contains(loc)) {
				properties.put("fillcolor", "green");
			}

			if (curStmt.getLabel().equals(program.getStart())) {
				properties.put("color", "green");
				properties.put("style", "filled,bold");
			} else if (curStmt instanceof RTLHalt) {
				properties.put("color", "orange");
				properties.put("style", "filled,bold");
			}
		} else {
			logger.info("No real statement for location " + loc);
		}

		return properties;
	}

	// Does not write a real graph, but still fits best into this class  
	public void writeDisassembly(Program program, String filename) {
		logger.info("Writing assembly file to " + filename);

		SetMultimap<AbsoluteAddress, CFAEdge> branchEdges = HashMultimap.create(); 
		SetMultimap<AbsoluteAddress, CFAEdge> branchEdgesRev = HashMultimap.create(); 
		if (!Options.noGraphs.getValue()) {
			for (CFAEdge e : program.getCFA()) {
				AbsoluteAddress sourceAddr = e.getSource().getAddress(); 
				AbsoluteAddress targetAddr = e.getTarget().getAddress();
				if (program.getInstruction(sourceAddr) instanceof BranchInstruction && !sourceAddr.equals(targetAddr)) {
					branchEdges.put(sourceAddr, e);
					branchEdgesRev.put(targetAddr, e);
				}
			}
		}
		
		try {
			FileWriter out = new FileWriter(filename);
			for (Map.Entry<AbsoluteAddress,Instruction> entry : program.getAssemblyMap().entrySet()) {
				AbsoluteAddress pc = entry.getKey();
				Instruction instr = entry.getValue();
				StringBuilder sb = new StringBuilder();
				SymbolFinder symFinder = program.getModule(pc).getSymbolFinder();
				if (symFinder.hasSymbolFor(pc)) {
					sb.append(Characters.NEWLINE);
					sb.append(symFinder.getSymbolFor(pc));
					sb.append(":").append(Characters.NEWLINE);
				}
				sb.append(pc).append(":\t");
				sb.append(instr.toString(pc.getValue(), symFinder));
				
				if (instr instanceof BranchInstruction) {
					Set<CFAEdge> targets = branchEdges.get(pc);
					sb.append("\t; targets: ");
					if (targets.isEmpty()) {
						sb.append("unresolved");
					} else {
						boolean first = true;
						for (CFAEdge e : targets) {
							if (first) first = false;
							else sb.append(", ");
							sb.append(e.getTarget().getAddress());
							sb.append('(').append(e.getKind()).append(')');
						}
					}
				}

				if (branchEdgesRev.containsKey(pc)) {
					Set<CFAEdge> referers = branchEdgesRev.get(pc);
					sb.append("\t; from: ");
					boolean first = true;
					for (CFAEdge e : referers) {
						if (first) first = false;
						else sb.append(", ");
						sb.append(e.getSource().getAddress());
						sb.append('(').append(e.getKind()).append(')');
					}
				}
				
				
				sb.append(Characters.NEWLINE);
				if (instr instanceof ReturnInstruction) sb.append(Characters.NEWLINE);
				out.write(sb.toString());
			}
			out.close();

		} catch (IOException e) {
			logger.fatal(e);
			return;
		}
	}
	
	public void writeAssemblyBBCFG(String filename) {
		ControlFlowGraph cfg = program.getCFG();

		// Create dot file
		GraphWriter gwriter = createGraphWriter(filename);
		if (gwriter == null) return;

		logger.info("Writing assembly CFG to " + gwriter.getFilename());
		try {
			for (BasicBlock bb : cfg.getBasicBlocks()) {
				AbsoluteAddress nodeAddr = bb.getFirst().getAddress();
				String nodeName = nodeAddr.toString();
				StringBuilder labelBuilder = new StringBuilder();
				String locLabel = program.getSymbolFor(nodeAddr);
				if (locLabel.length() > 20) locLabel = locLabel.substring(0, 20) + "...";
				labelBuilder.append(locLabel).append("\\n");

				for (Iterator<AbsoluteAddress> addrIt = bb.addressIterator(); addrIt.hasNext();) {
					AbsoluteAddress curAddr = addrIt.next();
					Instruction instr = program.getInstruction(curAddr);
					if (instr != null) {
						String instrString = instr.toString(curAddr.getValue(), program.getModule(curAddr).getSymbolFinder());
						instrString = instrString.replace("\t", " ");
						labelBuilder.append(instrString + "\\l");
					} else {
						//labelBuilder.append(curAddr.toString() + "\\l");
					}
					gwriter.writeNode(nodeName, labelBuilder.toString(), getNodeProperties(bb.getFirst().getLabel()));
				}
			}
			
			for (CFAEdge e : cfg.getBasicBlockEdges()) {
				if (e.getKind() == null) logger.error("Null kind? " + e);
				AbsoluteAddress sourceAddr = e.getSource().getAddress(); 
				AbsoluteAddress targetAddr = e.getTarget().getAddress();
				BasicBlock bb = (BasicBlock)e.getTransformer();
				
				String label = null;
				RTLLabel lastLoc = bb.getLast().getLabel();
				Instruction instr = program.getInstruction(lastLoc.getAddress());
				
				if (instr instanceof BranchInstruction) {
					BranchInstruction bi = (BranchInstruction)instr;
					if (bi.isConditional()) {
						// Get the original goto from the program (not the converted assume) 
						RTLStatement rtlGoto = program.getStatement(lastLoc);
						
						// If this is the fall-through edge, output F, otherwise T
						label = targetAddr.equals(rtlGoto.getNextLabel().getAddress()) ? "F" : "T";
					}
				}
				
				if (label != null)
					gwriter.writeLabeledEdge(sourceAddr.toString(), 
							targetAddr.toString(), 
							label,
							e.getKind().equals(CFAEdge.Kind.MAY) ? Color.BLACK : Color.GREEN);
				else
					gwriter.writeEdge(sourceAddr.toString(), 
							targetAddr.toString(), 
							e.getKind().equals(CFAEdge.Kind.MAY) ? Color.BLACK : Color.GREEN);

			}

			gwriter.close();
		} catch (IOException e) {
			logger.error("Cannot write to output file", e);
			return;
		}
	}

	
	public void writeAssemblyCFG(String filename) {
		Set<CFAEdge> edges = new HashSet<CFAEdge>(); 
		Set<RTLLabel> nodes = new HashSet<RTLLabel>();
		for (CFAEdge e : program.getCFA()) {
			AbsoluteAddress sourceAddr = e.getSource().getAddress(); 
			AbsoluteAddress targetAddr = e.getTarget().getAddress();
			if (!sourceAddr.equals(targetAddr)) {
				edges.add(e);
				nodes.add(e.getSource());
				nodes.add(e.getTarget());
			}
		}
		
		// Create dot file
		GraphWriter gwriter = createGraphWriter(filename);
		if (gwriter == null) return;

		logger.info("Writing assembly CFG to " + gwriter.getFilename());
		try {
			for (RTLLabel node : nodes) {
				AbsoluteAddress nodeAddr = node.getAddress();
				Instruction instr = program.getInstruction(nodeAddr);
				String nodeName = nodeAddr.toString();
				String nodeLabel = program.getSymbolFor(nodeAddr);
				
				if (instr != null) {
					String instrString = instr.toString(nodeAddr.getValue(), program.getModule(nodeAddr).getSymbolFinder());
					instrString = instrString.replace("\t", " ");
					gwriter.writeNode(nodeName, nodeLabel + "\\n" + instrString, getNodeProperties(node));
				} else {
					gwriter.writeNode(nodeName, nodeLabel, getNodeProperties(node));
				}
			}

			for (CFAEdge e : edges) {
				if (e.getKind() == null) logger.error("Null kind? " + e);
				AbsoluteAddress sourceAddr = e.getSource().getAddress(); 
				AbsoluteAddress targetAddr = e.getTarget().getAddress();
				
				String label = null;
				Instruction instr = program.getInstruction(sourceAddr);
				
				if (instr instanceof BranchInstruction) {
					BranchInstruction bi = (BranchInstruction)instr;
					if (bi.isConditional()) {
						// Get the original goto from the program (not the converted assume) 
						RTLStatement rtlGoto = program.getStatement(e.getSource());
						
						// If this is the fall-through edge, output F, otherwise T
						label = targetAddr.equals(rtlGoto.getNextLabel().getAddress()) ? "F" : "T";
					}
				}
				
				if (label != null)
					gwriter.writeLabeledEdge(sourceAddr.toString(), 
							targetAddr.toString(), 
							label,
							e.getKind().equals(CFAEdge.Kind.MAY) ? Color.BLACK : Color.GREEN);
				else
					gwriter.writeEdge(sourceAddr.toString(), 
							targetAddr.toString(), 
							e.getKind().equals(CFAEdge.Kind.MAY) ? Color.BLACK : Color.GREEN);
			}

			gwriter.close();
		} catch (IOException e) {
			logger.error("Cannot write to output file", e);
			return;
		}

		
	}

	public void writeControlFlowAutomaton(String filename) {
		writeControlFlowAutomaton(filename, (ReachedSet)null);
	}

	public void writeControlFlowAutomaton(String filename, ReachedSet reached) {
		Set<RTLLabel> nodes = new HashSet<RTLLabel>();
		for (CFAEdge e : program.getCFA()) {
			nodes.add(e.getTarget());
			nodes.add(e.getSource());
		}

		// Create dot file
		GraphWriter gwriter = createGraphWriter(filename);
		if (gwriter == null) return;

		logger.info("Writing CFA to " + gwriter.getFilename());
		try {
			for (RTLLabel node : nodes) {
				String nodeName = node.toString();
				StringBuilder labelBuilder = new StringBuilder();
				labelBuilder.append(nodeName);
				if (reached != null) {
					labelBuilder.append("\n");
					if (reached.where(node).isEmpty()) {
						logger.warn("No reached states for location " + node);
					}
					for (AbstractState a : reached.where(node)) {
						labelBuilder.append(a.toString());
						labelBuilder.append("\n");
					}
				}
				gwriter.writeNode(nodeName, labelBuilder.toString(), getNodeProperties(node));
			}

			for (CFAEdge e : program.getCFA()) {
				if (e.getKind() == null) logger.error("Null kind? " + e);
				gwriter.writeLabeledEdge(e.getSource().toString(), 
						e.getTarget().toString(), 
						e.getTransformer().toString(),
						e.getKind().equals(CFAEdge.Kind.MAY) ? Color.BLACK : Color.GREEN);
			}

			gwriter.close();
		} catch (IOException e) {
			logger.error("Cannot write to output file", e);
			return;
		}
	}
	
	public void writeControlFlowAutomaton(String filename, Map<RTLLabel, Object> reached) {
		Set<RTLLabel> nodes = new HashSet<RTLLabel>();
		for (CFAEdge e : program.getCFA()) {
			nodes.add(e.getTarget());
			nodes.add(e.getSource());
		}

		// Create dot file
		GraphWriter gwriter = createGraphWriter(filename);
		if (gwriter == null) return;

		logger.info("Writing CFA to " + gwriter.getFilename());
		try {
			for (RTLLabel node : nodes) {
				String nodeName = node.toString();
				StringBuilder labelBuilder = new StringBuilder();
				labelBuilder.append(nodeName);
				if (reached != null) {
					labelBuilder.append("\n");
					Object info = reached.get(node);
					if (info == null)
						logger.warn("No information for location " + node);
					else
						labelBuilder.append(info.toString());
				}
				gwriter.writeNode(nodeName, labelBuilder.toString(), getNodeProperties(node));
			}

			for (CFAEdge e : program.getCFA()) {
				if (e.getKind() == null) logger.error("Null kind? " + e);
				gwriter.writeLabeledEdge(e.getSource().toString(), 
						e.getTarget().toString(), 
						e.getTransformer().toString(),
						e.getKind().equals(CFAEdge.Kind.MAY) ? Color.BLACK : Color.GREEN);
			}

			gwriter.close();
		} catch (IOException e) {
			logger.error("Cannot write to output file", e);
			return;
		}
	}
	
	public void writeCallGraph(String filename, SetMultimap<RTLLabel, RTLLabel> callGraph) {
		// Create dot file
		GraphWriter gwriter = createGraphWriter(filename);
		if (gwriter == null) return;
		
		Set<RTLLabel> nodes = new HashSet<RTLLabel>();
		
		logger.info("Writing callgraph to " + gwriter.getFilename());
		try {
			for (Map.Entry<RTLLabel, RTLLabel> e : callGraph.entries()) {
				nodes.add(e.getKey());
				nodes.add(e.getValue());
				gwriter.writeEdge(e.getKey().toString(), 
						e.getValue().toString());
			}
			
			for (RTLLabel node : nodes) {
				gwriter.writeNode(node.toString(), node.toString(), getNodeProperties(node));
			}

			gwriter.close();
		} catch (IOException e) {
			logger.error("Cannot write to output file", e);
			return;
		}		
	}

	public void writeART(String filename, AbstractReachabilityTree art) {
		Map<String,String> startNode = new HashMap<String, String>();
		Map<String,String> endNode = new HashMap<String, String>();
		startNode.put("color", "green");
		startNode.put("style", "filled,bold");
		endNode.put("color", "red");
		endNode.put("style", "filled,bold");

		// Create dot file
		GraphWriter gwriter = createGraphWriter(filename);
		if (gwriter == null) return;

		logger.info("Writing ART to " + gwriter.getFilename());
		try {
			Deque<AbstractState> worklist = new LinkedList<AbstractState>();
			//Set<AbstractState> visited = new HashSet<AbstractState>();
			worklist.add(art.getRoot());
			//visited.addAll(worklist);
			while (!worklist.isEmpty()) {
				AbstractState curState = worklist.removeFirst();

				String nodeName = curState.getIdentifier();
				Map<String, String> properties = null;
				if (curState == art.getRoot())
					properties = startNode;
				if (program.getStatement((RTLLabel)curState.getLocation()) instanceof RTLHalt)
					properties = endNode;
				StringBuilder nodeLabel = new StringBuilder();
				nodeLabel.append(curState.getIdentifier());
				//nodeLabel.append("\\n");
				//nodeLabel.append(curState);
				for (AbstractState coverState : art.getCoveringStates(curState)) {
					nodeLabel.append("Covered by ").append(coverState.getIdentifier()).append("\\n");
				}

				gwriter.writeNode(nodeName, nodeLabel.toString(), properties);

				for (AbstractState nextState : art.getChildren(curState)) {
					//if (!visited.contains(nextState)) {
					worklist.add(nextState);
					//visited.add(nextState);
					gwriter.writeEdge(nodeName, nextState.getIdentifier());
					//}
				}
			}			
			gwriter.close();
		} catch (IOException e) {
			logger.error("Cannot write to output file", e);
			return;
		}

	}
	
	private VpcLiftedCFG getVpcGraph(AbstractReachabilityTree art) {
		if (vcfg == null)
			vcfg = new VpcLiftedCFG(art);
		return vcfg;
	}
	
	public void writeVpcGraph(String filename, AbstractReachabilityTree art, ReachedSet reached) {
		VpcLiftedCFG vCfg = getVpcGraph(art);
		// Create dot file
		GraphWriter gwriter = createGraphWriter(filename);
		if (gwriter == null) return;
		
		logger.info("Writing VPC-lifted CFA to " + gwriter.getFilename());
		try {
			for (VpcLocation node : vCfg.getNodes()) {
				String nodeName = node.toString();
				StringBuilder labelBuilder = new StringBuilder();
				labelBuilder.append(nodeName);
				gwriter.writeNode(nodeName, labelBuilder.toString(), getNodeProperties(node.getLocation()));
			}

			for (Map.Entry<VpcLocation, VpcLocation> e : vCfg.getEdges()) {
				gwriter.writeLabeledEdge(e.getKey().toString(), 
						e.getValue().toString(), 
						program.getStatement(e.getKey().getLocation()).toString());
			}

			gwriter.close();
		} catch (IOException e) {
			logger.error("Cannot write to output file", e);
			return;
		}
	}

	public void writeVpcBBGraph(String filename, AbstractReachabilityTree art, ReachedSet reached) {
		
		VpcLiftedCFG vCfg = getVpcGraph(art);
		
		// Create dot file
		GraphWriter gwriter = createGraphWriter(filename);
		if (gwriter == null) return;

		logger.info("Writing VPC-lifted CFG to " + gwriter.getFilename());
		try {
			for (VpcLocation vpcLoc : vCfg.getBasicBlockNodes()) {
				
				RTLLabel nodeAddr = vpcLoc.getLocation();
				String nodeName = vpcLoc.toString();
				StringBuilder labelBuilder = new StringBuilder();
				String locLabel = program.getSymbolFor(nodeAddr);
				if (locLabel.length() > 20) locLabel = locLabel.substring(0, 20) + "...";
				labelBuilder.append(locLabel).append(" @ ").append(vpcLoc.getVPC()).append("\\n");
				
				BasicBlock bb = vCfg.getBasicBlock(vpcLoc);

				for (RTLStatement stmt : bb) {
					labelBuilder.append(stmt.toString() + "\\l");
				}
				gwriter.writeNode(nodeName, labelBuilder.toString(), getNodeProperties(bb.getFirst().getLabel()));
			}

			for (Map.Entry<VpcLocation, VpcLocation> e : vCfg.getBasicBlockEdges()) {
				VpcLocation sourceAddr = e.getKey(); 
				VpcLocation targetAddr = e.getValue();
				BasicBlock bb = vCfg.getBasicBlock(sourceAddr);
				assert(bb != null);
				
				String label = null;
				RTLStatement terminator = bb.getLast();
				
				if (terminator instanceof RTLGoto) {
					RTLGoto bi = (RTLGoto)terminator;
					if (!bi.getCondition().equals(ExpressionFactory.TRUE)) {
						// Get the original goto from the program (not the converted assume) 
						// If this is the fall-through edge, output F, otherwise T
						label = targetAddr.getLocation().equals(bi.getNextLabel()) ? "F" : "T";
					}
				}
				
				if (label != null)
					gwriter.writeLabeledEdge(sourceAddr.toString(), 
							targetAddr.toString(), 
							label);
				else
					gwriter.writeEdge(sourceAddr.toString(), 
							targetAddr.toString());

			}

			gwriter.close();
		} catch (IOException e) {
			logger.error("Cannot write to output file", e);
			return;
		}
	}


	
	public void writeVpcAsmBBGraph(String filename, AbstractReachabilityTree art, ReachedSet reached) {
		
		VpcLiftedCFG vCfg = getVpcGraph(art);
		
		// Create dot file
		GraphWriter gwriter = createGraphWriter(filename);
		if (gwriter == null) return;

		logger.info("Writing VPC-lifted CFG to " + gwriter.getFilename());
		try {
			for (VpcLocation vpcLoc : vCfg.getBasicBlockNodes()) {
				
				AbsoluteAddress nodeAddr = vpcLoc.getLocation().getAddress();
				String nodeName = vpcLoc.toString();
				StringBuilder labelBuilder = new StringBuilder();
				String locLabel = program.getSymbolFor(nodeAddr);
				if (locLabel.length() > 20) locLabel = locLabel.substring(0, 20) + "...";
				labelBuilder.append(locLabel).append(" @ ").append(vpcLoc.getVPC()).append("\\n");
				
				BasicBlock bb = vCfg.getBasicBlock(vpcLoc);

				for (Iterator<AbsoluteAddress> addrIt = bb.addressIterator(); addrIt.hasNext();) {
					AbsoluteAddress curAddr = addrIt.next();
					Instruction instr = program.getInstruction(curAddr);
					if (instr != null) {
						String instrString = instr.toString(curAddr.getValue(), program.getModule(curAddr).getSymbolFinder());
						instrString = instrString.replace("\t", " ");
						labelBuilder.append(instrString + "\\l");
					} else {
						//labelBuilder.append("no instruction\\l");
					}
				}
				gwriter.writeNode(nodeName, labelBuilder.toString(), getNodeProperties(bb.getFirst().getLabel()));
			}

			for (Map.Entry<VpcLocation, VpcLocation> e : vCfg.getBasicBlockEdges()) {
				VpcLocation sourceAddr = e.getKey(); 
				VpcLocation targetAddr = e.getValue();
				BasicBlock bb = vCfg.getBasicBlock(sourceAddr);
				assert(bb != null);
				
				String label = null;
				RTLLabel lastLoc = bb.getLast().getLabel();
				Instruction instr = program.getInstruction(lastLoc.getAddress());
				
				if (instr instanceof BranchInstruction) {
					BranchInstruction bi = (BranchInstruction)instr;
					if (bi.isConditional()) {
						// Get the original goto from the program (not the converted assume) 
						RTLStatement rtlGoto = program.getStatement(lastLoc);
						assert(rtlGoto instanceof RTLGoto);
						
						// If this is the fall-through edge, output F, otherwise T
						label = targetAddr.getLocation().equals(rtlGoto.getNextLabel()) ? "F" : "T";
					}
				}
				
				if (label != null)
					gwriter.writeLabeledEdge(sourceAddr.toString(), 
							targetAddr.toString(), 
							label);
				else
					gwriter.writeEdge(sourceAddr.toString(), 
							targetAddr.toString());

			}

			gwriter.close();
		} catch (IOException e) {
			logger.error("Cannot write to output file", e);
			return;
		}
	}


}
