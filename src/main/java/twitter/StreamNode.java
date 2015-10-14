package twitter;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;
import org.dsa.iot.dslink.util.json.JsonObject;
import twitter4j.FilterQuery;
import twitter4j.StallWarning;
import twitter4j.Status;
import twitter4j.StatusDeletionNotice;
import twitter4j.StatusListener;
import twitter4j.TwitterObjectFactory;
import twitter4j.TwitterStream;
import twitter4j.TwitterStreamFactory;
import twitter4j.conf.ConfigurationBuilder;


public class StreamNode implements StatusListener {
	
	private final Node node;
	final TwitterConn conn;
	private final boolean filtered;
	private TwitterStream twitterStream;
	
	public StreamNode(TwitterConn conn, Node node) {
		this.node = node;
		this.conn = conn;
		filtered = node.getAttribute("filtered").getBool();
	}
	
	void init() {
		
		ConfigurationBuilder cb = new ConfigurationBuilder();
		cb.setDebugEnabled(true)
  			.setOAuthConsumerKey(conn.node.getAttribute("consumer key").getString())
  			.setOAuthConsumerSecret(conn.node.getAttribute("consumer secret").getString())
  			.setOAuthAccessToken(conn.accessToken.getToken())
  			.setOAuthAccessTokenSecret(conn.accessToken.getTokenSecret())
  			.setJSONStoreEnabled(true);
		TwitterStreamFactory tsf = new TwitterStreamFactory(cb.build());
		twitterStream = tsf.getInstance();
		twitterStream.addListener(this);
		conn.openStreams.add(twitterStream);
		node.createChild("remove").setAction(new Action(Permission.READ, new EndStreamHandler())).build().setSerializable(false);
		
		if (filtered) {
			Value alllocstrings = node.getAttribute("locations");
			Value alltrack = node.getAttribute("track");
			Value allfollowstrings = node.getAttribute("follow");
			//Value count = node.getAttribute("count");
			Value alllang = node.getAttribute("language");
			
			Action act = new Action(Permission.READ, new EditHandler());
			act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));
			act.addParameter(new Parameter("locations", ValueType.STRING, alllocstrings));
			act.addParameter(new Parameter("track", ValueType.STRING, alltrack));
			act.addParameter(new Parameter("follow", ValueType.STRING, allfollowstrings));
			//act.addParameter(new Parameter("count", ValueType.NUMBER, count));
			act.addParameter(new Parameter("language", ValueType.STRING, alllang));
			Node anode = node.getChild("edit");
			if (anode == null) node.createChild("edit").setAction(act).build().setSerializable(false);
			else anode.setAction(act);
			
			FilterQuery fq = new FilterQuery();
			boolean locsSet = (alllocstrings != null && alllocstrings.getString().length() > 0);
			boolean trackSet = (alltrack != null && alltrack.getString().length() > 0);
			boolean followSet = (allfollowstrings != null && allfollowstrings.getString().length() > 0);
			if ((!locsSet) && (!trackSet) && (!followSet)) {
//				builder = err.createChild("filter error message");
//				builder.setValue(new Value("Must specify at least one of locations, track, and follow"));
//				builder.build();
			}
			if (locsSet) {
				String[] locstrings = alllocstrings.getString().split(";");
				int loclength = locstrings.length;
				double[][] locs = new double[loclength][];
				for (int i=0; i<loclength; i++) {
					String[] innerLocstrings = locstrings[i].split(",");
					int innerlength = innerLocstrings.length;
					double[] inner = new double[innerlength];
					for (int j=0; j<innerlength; j++) {
						inner[j] = Double.parseDouble(innerLocstrings[j]);
					}
					locs[i] = inner;
				}
				fq.locations(locs);
			}
			if (trackSet) {
				String[] track = alltrack.getString().split(",");
				fq.track(track);
			}
			if (followSet) {
				String[] followstrings = allfollowstrings.getString().split(",");
				int followlength = followstrings.length;
				long[] follow = new long[followlength];
				for (int i=0; i<followlength; i++) {
					follow[i] = Long.parseLong(followstrings[i]);
				}
				fq.follow(follow);
			}
			/*if (count != null) {
				fq.count(count.getNumber().intValue());
			}*/
			if (alllang != null && alllang.getString().length() > 0) {
				String[] lang = alllang.getString().split(",");
				fq.language(lang);
			}
			twitterStream.filter(fq);
		} else {			
			twitterStream.sample();
		}
	}
	
	private class EndStreamHandler implements Handler<ActionResult> {
		public void handle (ActionResult event) {
			remove();
		}
	}
	
	private void remove() {
		stop();
		node.clearChildren();
		node.getParent().removeChild(node);
	}
	
	private void stop() {
		twitterStream.cleanUp();
		twitterStream.shutdown();
		conn.openStreams.remove(twitterStream);
	}
	
	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name").getString();
			Value alllocstrings = event.getParameter("locations");
			Value alltrack = event.getParameter("track");
			Value allfollowstrings = event.getParameter("follow");
			//Value count = event.getParameter("count");
			Value alllang = event.getParameter("language");
			
			node.removeAttribute("locations");
			node.removeAttribute("track");
			node.removeAttribute("follow");
			//node.removeAttribute("count");
			node.removeAttribute("language");
			
			if (node.getName().equals(name)) {
				if (alllocstrings!=null) node.setAttribute("locations", alllocstrings);
				if (alltrack!=null) node.setAttribute("track", alltrack);
				if (allfollowstrings!=null) node.setAttribute("follow", allfollowstrings);
				//if (count!=null) node.setAttribute("count", count);
				if (alllang!=null) node.setAttribute("language", alllang);
				stop();
				init();
			} else {
				Node newNode = node.getParent().createChild(name).setValueType(ValueType.MAP).build();
				newNode.setAttribute("filtered", new Value(true));
				if (alllocstrings!=null) newNode.setAttribute("locations", alllocstrings);
				if (alltrack!=null) newNode.setAttribute("track", alltrack);
				if (allfollowstrings!=null) newNode.setAttribute("follow", allfollowstrings);
				//if (count!=null) newNode.setAttribute("count", count);
				if (alllang!=null) newNode.setAttribute("language", alllang);
				remove();
				StreamNode sn = new StreamNode(conn, newNode);
				sn.init();
			}
		}
	}

	public void onException(Exception arg0) {
		// TODO Auto-generated method stub
		
	}

	public void onDeletionNotice(StatusDeletionNotice arg0) {
		// TODO Auto-generated method stub
		
	}

	public void onScrubGeo(long arg0, long arg1) {
		// TODO Auto-generated method stub
		
	}

	public void onStallWarning(StallWarning arg0) {
		// TODO Auto-generated method stub
		
	}

	public void onStatus(Status status) {
		
//		Node nameNode = tweetNode.getChild("name");
//		Node textNode = tweetNode.getChild("text");
		
//		nameNode.setValue(new Value(status.getUser().getName()));
//		textNode.setValue(new Value(status.getText()));

		String jsonStr = TwitterObjectFactory.getRawJSON(status);
		JsonObject jsonObj = new JsonObject(jsonStr);
		node.setValue(new Value(jsonObj));
	}

	public void onTrackLimitationNotice(int arg0) {
		// TODO Auto-generated method stub
		
	}


}
