/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package agents;

import gui.ArtistManagerFrame;
import gui.ArtistManagerInterface;
import jade.content.ContentElement;
import jade.content.lang.Codec.CodecException;
import jade.content.lang.sl.SLCodec;
import jade.content.onto.OntologyException;
import jade.content.onto.basic.Action;
import jade.content.onto.basic.Result;
import jade.core.AID;
import jade.core.Agent;
import jade.core.Location;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.FSMBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.SequentialBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.domain.FIPANames;
import jade.domain.JADEAgentManagement.QueryPlatformLocationsAction;
import jade.domain.mobility.MobilityOntology;
import jade.gui.GuiAgent;
import jade.gui.GuiEvent;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;
import jade.util.leap.Iterator;
import java.awt.EventQueue;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;
import model.Artifact;

/**
 *
 * @author M&M
 */
public class ArtistManagerAgent extends GuiAgent {

    private transient ArtistManagerInterface UI;
    public static final int close = 0;
    public static final int auction = 1;
    private Location mainLocation;
    private AID artistManager;
    private Artifact art;
    private float highestPrice;
    private float lowestPrice;
    private AuctionLocalResult result;

    @Override
    protected void setup() {
        // Register language and ontology
        getContentManager().registerLanguage(new SLCodec());
        getContentManager().registerOntology(MobilityOntology.getInstance());


        /* Create and display the form */
        final ArtistManagerAgent agent = this;
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                UI = new ArtistManagerFrame(agent);
                UI.open();
            }
        });

        System.out.println("Artist Manager " + getAID().getName() + " is ready...");
    }

    @Override
    protected void takeDown() {

        System.out.println("Artist Manager " + getAID().getName() + " is terminating...");

    }

    public void sellArtifact() {
        UI.message("Starting auction...");
        addBehaviour(new DutchAuctionBehaviour(this, art, highestPrice, lowestPrice));
    }

    @Override
    protected void onGuiEvent(GuiEvent ge) {
        switch (ge.getType()) {
            case close:
                UI.close();
                doDelete();
                break;
            case auction:
                String[] args = (String[]) ge.getParameter(0);
                multiLocationAuction(args);
                break;
        }
    }

    @Override
    protected void afterClone() {
        //In target container already
        UI = new ArtistManagerInterface() {
            String name = getLocalName();

            @Override
            public void open() {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public void close() {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public void message(String msg) {
                System.out.println(name + ": " + msg);
            }
        };

        System.out.println("clone Artist Manager " + getLocalName() + " is ready (at " + here().getName() + ")...");

        sellArtifact();
    }

    @Override
    protected void afterMove() {
        //In home container
        addBehaviour(new AfterMovingBack(this));
    }

    private void multiLocationAuction(String[] args) {
        try {
            UI.message("Starting auction in multiple location...");

            //Setting auction details for clones
            String name = args[0];
            String creator = args[1];
            String description = args[2];
            String style = args[3];
            art = new Artifact(name, creator, description, style);
            highestPrice = Float.parseFloat(args[4]);
            lowestPrice = Float.parseFloat(args[5]);

            mainLocation=here();
            artistManager = getAID();

            //get available location with AMS      
            UI.message("looking for containers...");
            Map<String, Location> locations = new HashMap<>();

            sendRequest(new Action(getAMS(), new QueryPlatformLocationsAction()));
            
            // receive responses fro AMS
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchSender(getAMS()),
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM));
            ACLMessage resp = blockingReceive(mt);
            ContentElement ce = getContentManager().extractContent(resp);
            Result res = (Result) ce;
            Iterator it = res.getItems().iterator();
            while (it.hasNext()) {
                Location loc = (Location) it.next();
                locations.put(loc.getName(), loc);
            }

            //Removing main location and main-container location
            locations.remove(mainLocation.getName());
            locations.remove("Main-Container");

            //Cloning artist manager
            UI.message("start cloning:");
            MessageTemplate resultsMt;
            for (Entry<String, Location> e : locations.entrySet()) {
                final String cloneName = "clone-" + getLocalName() + "@" + e.getKey();
                final Location cloneLocation = e.getValue();
                addBehaviour(new OneShotBehaviour(this) {
                    @Override
                    public void action() {
                        if (!getName().contains("clone")) {
                            doClone(cloneLocation, cloneName);
                        }
                    }
                });
                UI.message("\tClone agent: " + cloneName + ". Created at " + cloneLocation.getName());
            }
            resultsMt=MessageTemplate.MatchPerformative(ACLMessage.INFORM);

            //Preparing for collecting results
            UI.message("Collecting results:");
            int noOfContainers;
            noOfContainers = locations.size();
            addBehaviour(new CollectingResults(this, noOfContainers, resultsMt));
        } catch (CodecException | OntologyException ex) {
            Logger.getLogger(ArtistManagerAgent.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    void sendRequest(Action action) {
        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        request.setLanguage(new SLCodec().getName());
        request.setOntology(MobilityOntology.getInstance().getName());
        try {
            getContentManager().fillContent(request, action);
            request.addReceiver(action.getActor());
            send(request);
        } catch (CodecException | OntologyException ex) {
            Logger.getLogger(ArtistManagerAgent.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private class DutchAuctionBehaviour extends FSMBehaviour {

        private List<AID> biders;
        private AID winnerCurator;
        private List<AID> losers = new ArrayList<>();
        private MessageTemplate pattern; 
        private double reductionRate;
        private double priceToBid;
        private int proposeCounter;
        double temp;

        public DutchAuctionBehaviour(final Agent agent, final Artifact art, final float highestPrice, final float lowestPrice) {
            super(agent);

            biders = new ArrayList<>();
            pattern= MessageTemplate.MatchProtocol(FIPANames.InteractionProtocol.FIPA_DUTCH_AUCTION);
            reductionRate = (highestPrice - lowestPrice)* 0.1;
            priceToBid = highestPrice;
            
 
            //serach for existing curators
            registerFirstState(new OneShotBehaviour(agent) {
                @Override
                public void action() {
                    UI.message("Searching for curators...");
                    DFAgentDescription template = new DFAgentDescription();
                    ServiceDescription sd = new ServiceDescription();
                    sd.setType(here().getName()+" curator");
                    template.addServices(sd);
                    try {
                        DFAgentDescription[] result = DFService.search(myAgent, template);
                        if (result.length == 0) {
                            UI.message("No curators were found...");
                        }

                        UI.message("Following curators were found:");
                        for (int i = 0; i < result.length; i++) {
                            biders.add(result[i].getName());
                            UI.message("\t" + biders.get(i).getName());
                        }
                    } catch (FIPAException ex) {
                        Logger.getLogger(ArtistManagerAgent.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }

                @Override
                public int onEnd() {
                    return biders.isEmpty() ? 0 : 1;
                }
            }, "finding biders");

            //Inform all biders about the item to bid
            registerState(new OneShotBehaviour(agent) {
                @Override
                public void action() {
                    
                    ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                    msg.setProtocol(FIPANames.InteractionProtocol.FIPA_DUTCH_AUCTION);
                    msg.setLanguage(Integer.toString(biders.size()));
                    try {
                        msg.setContentObject((Artifact) art);
                    } catch (IOException ex) {
                        Logger.getLogger(ArtistManagerAgent.class.getName()).log(Level.SEVERE, null, ex);
                    }
                    for (AID a : biders) {
                        msg.addReceiver(a);
                    }
                    myAgent.send(msg);
                    UI.message("biders informed about start of auction...");
                }
            }, "inform biders");
            //call biders to propose
            registerState(new OneShotBehaviour(agent) {
                @Override
                public void action() {
                    UI.message("Auctioneer is calling biders for proposal [price=" + priceToBid + "]...");

                    ACLMessage msg = new ACLMessage(ACLMessage.CFP);
                    msg.setProtocol(FIPANames.InteractionProtocol.FIPA_DUTCH_AUCTION);
                    msg.setLanguage(Integer.toString(biders.size()));
                    msg.setContent(Double.toString(priceToBid));
                    for (AID a: biders) {
                        msg.addReceiver(a);
                        UI.message("\tSending cfp to [" + a.getName() + "]");
                    }
                    myAgent.send(msg);
                    UI.message("Auctioneer is waiting for bid");
                    proposeCounter = 0;
                }
            }, "call for proposal");
            
            //receiving propose message from biders
            registerState(new Behaviour(agent) {
                @Override
                public void action() {
                    ACLMessage msg = myAgent.receive(pattern);
                    if (msg != null) {
                        switch (msg.getPerformative()) {
                            case ACLMessage.PROPOSE:
                                if (winnerCurator == null) {
                                    winnerCurator = msg.getSender();
                                } else {
                                    losers.add(msg.getSender());
                                }
                                break;
                            case ACLMessage.REFUSE:
                                break;
                        }
                        proposeCounter++;
                        UI.message("\t#" + proposeCounter + " [" + msg.getSender().getName() + "] responded with " + ACLMessage.getPerformative(msg.getPerformative()));
                    } else {
                        block();
                    }
                }

                @Override
                public int onEnd() {
                    return winnerCurator != null ? 1 : 0;
                }

                @Override
                public boolean done() {
                    return proposeCounter==biders.size();
                }
            }, "receiving proposals");
            
            // reducing the price if no proposal has been received
            registerState(new OneShotBehaviour(agent) {
                @Override
                public void action() {
                    UI.message("reducing price for the next round.....");
                    // reduce the price by the specified rate.
                    temp=priceToBid;
                    if(priceToBid!=lowestPrice){
                        priceToBid -= reductionRate;
                        if (priceToBid<lowestPrice){
                        priceToBid=lowestPrice;
                    }
                    }
                }

                @Override
                public int onEnd() {
                    return temp == lowestPrice ? 1 : 0;
                }
            }, "reducing price");
            // accepting received proposal if any
            registerState(new OneShotBehaviour(agent) {
                @Override
                public void action() {
                    //inform the winner

                    //Inform the losers whoes propose was rejected due to late response
                    ACLMessage rejMsg = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
                    rejMsg.setProtocol(FIPANames.InteractionProtocol.FIPA_DUTCH_AUCTION);
                    for (AID l : losers) {
                        rejMsg.addReceiver(l);
                    }
                    myAgent.send(rejMsg);

                    UI.message("Artifact "+ art.getName()+" is reserved for: "+ winnerCurator.getName()+"at price:"+ priceToBid);
                     UI.message("late responders are:\n ");
                    for (AID l : losers) {
                        UI.message("\t" + l.getName() + "\n");
                    }
                }
            }, "Accepting proposal");
            
            // closing auction if there is no curator
            registerState(new OneShotBehaviour(agent){
                @Override
                public void action() {
                    UI.message("Auction closed: No curator could be found\n");
                }
            }, "No bider");
            
            //end of the auction either selling or not selling the item
            registerState(new OneShotBehaviour(agent) {
                @Override
                public void action() {
                    //inform all biders about the end of the auction
                    ACLMessage informMsg = new ACLMessage(ACLMessage.INFORM);
                    informMsg.setProtocol(FIPANames.InteractionProtocol.FIPA_DUTCH_AUCTION);
                    informMsg.setContent("Auction ended");
                    for (AID a : biders) {
                        informMsg.addReceiver(a);
                    }
                    myAgent.send(informMsg);

                    UI.message("Auction finished: be ready for the next item....");
                }
            }, "End of the auction");
            
            //returning back to the home container
            registerLastState(new OneShotBehaviour(agent) {
                @Override
                public void action() {
                    if (winnerCurator != null) {
                        
                        // result of the auction
                        result = new AuctionLocalResult(winnerCurator, here(), priceToBid);
                        
                    } else {
                        result = null;
                    }
                    myAgent.doMove(mainLocation);
                    UI.message("moved back");
                }
            }, "return home");
            

            

            registerTransition("finding biders", "No bider", 0);
            registerTransition("finding biders", "inform biders", 1);
            registerDefaultTransition("inform biders", "call for proposal");
            registerDefaultTransition("call for proposal", "receiving proposals");
            registerTransition("receiving proposals", "reducing price", 0);
            registerTransition("receiving proposals", "Accepting proposal", 1);
            registerTransition("reducing price", "call for proposal", 0);
            registerTransition("reducing price", "End of the auction", 1);
            registerDefaultTransition("Accepting proposal", "End of the auction");
            registerDefaultTransition("End of the auction", "return home");
            registerDefaultTransition("No bider", "return home");
        }
    }

    private class CollectingResults extends Behaviour {
        private final int noOfContainers;
        private int noOfResponses;
        private final MessageTemplate mt;
        private AID winnerCurator;
        private List<AID> loosers = new ArrayList<>();
        private double winPrice = 0;
        private Location winLocation;

        public CollectingResults(Agent a, int locationsCount, MessageTemplate mt) {
            super(a);
            this.noOfContainers = locationsCount;
            this.mt = mt;
        }

        @Override
        public void action() {
            // waiting to receive result from clones
            ACLMessage response = myAgent.receive(mt);
            if (response == null) {
                block();
                return;
            }

            ++noOfResponses;
            try {
                AuctionLocalResult result = (AuctionLocalResult) response.getContentObject();
                UI.message("\tResponse " + noOfResponses + ":");
                if (result != null) {
                    AID w = result.winner;
                    Location location = result.location;
                    double price = result.price;


                    UI.message("\t\tWinner: " + w.getLocalName());
                    UI.message("\t\tLocation: " + location.getName());
                    UI.message("\t\tPrice: " + price);
                    
                    if (price > winPrice) {
                        if (winnerCurator != null) {
                            loosers.add(winnerCurator);
                        }

                        winPrice = price;
                        winnerCurator = w;
                        winLocation = location;
                    } else {
                        loosers.add(w);
                        
                    }
                } else {
                    UI.message("\t\tNo bids");
                }
            } catch (UnreadableException ex) {
                Logger.getLogger(ArtistManagerAgent.class.getName()).log(Level.SEVERE, null, ex);
            }

            if (noOfResponses == noOfContainers) {
                UI.message("Auction is finished:");
                if (winnerCurator != null) {
                    //Respond to the winner
                    ACLMessage acceptMsg = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
                    acceptMsg.setProtocol(FIPANames.InteractionProtocol.FIPA_DUTCH_AUCTION);
                    try {
                         acceptMsg.setContentObject(art);
                    } catch (IOException ex) {
                        Logger.getLogger(ArtistManagerAgent.class.getName()).log(Level.SEVERE, null, ex);
                    }
                    acceptMsg.addReceiver(winnerCurator);
                    myAgent.send(acceptMsg);

                    //Respond to the loosers
                    if (loosers.size()>0) {
                        for (int i = 0; i < loosers.size(); i++) {
                            ACLMessage rejectMsg = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
                            rejectMsg.setProtocol(FIPANames.InteractionProtocol.FIPA_DUTCH_AUCTION);
                            rejectMsg.addReceiver(loosers.get(i));
                            myAgent.send(rejectMsg);
                        }
                    }
                    UI.message("Artifact "+ art.getName()+" is soled to: "+ winnerCurator.getLocalName()+" at "+ winLocation.getName()+" at price: "+ winPrice);
                } else {
                    UI.message("\tNo bids");
                }
            }
        }

        @Override
        public boolean done() {
            return noOfResponses == noOfContainers;
        }
    }

    private class AfterMovingBack extends SequentialBehaviour {
        public AfterMovingBack(Agent a) {
            super(a);

            //sending result to the main auction manager agent
            addSubBehaviour(new OneShotBehaviour(a) {
                @Override
                public void action() {
                    try {
                        ACLMessage informMsg = new ACLMessage(ACLMessage.INFORM);
                        informMsg.setContentObject(result);
                        informMsg.addReceiver(artistManager);
                        myAgent.send(informMsg);
                    } catch (IOException ex) {
                        Logger.getLogger(ArtistManagerAgent.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            });

            // clone agent suicides 
            addSubBehaviour(new OneShotBehaviour(a) {
                @Override
                public void action() {
                    doDelete();
                }
            });
        }
    }

    private class AuctionLocalResult implements Serializable {
        public AID winner;
        public Location location;
        public double price;

        public AuctionLocalResult(AID winner, Location location, double price) {
            this.winner = winner;
            this.location = location;
            this.price = price;
        }
    }
}