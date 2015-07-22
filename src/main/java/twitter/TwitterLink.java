package twitter;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.serializer.Deserializer;
import org.dsa.iot.dslink.serializer.Serializer;
import org.vertx.java.core.Handler;

public class TwitterLink {
//	private static final Logger LOGGER;
//	static {
//		LOGGER = LoggerFactory.getLogger(TwitterLink.class);
//	}
	
	private Node node;
	final Serializer copySerializer;
	final Deserializer copyDeserializer;
	
	private TwitterLink(Node node, Serializer copyser, Deserializer copydeser) {
		this.node = node;
		this.copySerializer = copyser;
		this.copyDeserializer = copydeser;
	}
	
	public static void start(Node parent, Serializer ser, Deserializer deser) {
		Node node = parent;
		final TwitterLink twit = new TwitterLink(node, ser, deser);
		twit.init();
	}
	
	private void init() {
		restoreLastSession();
		
		Action act = new Action(Permission.READ, new ConnectHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		act.addParameter(new Parameter("consumer key", ValueType.STRING, new Value("rqKA5pOYfQhDidbSh4nc8WIXx")));
		act.addParameter(new Parameter("consumer secret", ValueType.STRING, new Value("MxRQ5zROUgyMRRJXBGQkeMtubOZqimbSsxT5GyhxXOmDtzQ6ox")));
		node.createChild("add connection").setAction(act).build().setSerializable(false);
			
	}
	
	private void restoreLastSession() {
		if (node.getChildren() == null) return;
		for (Node child: node.getChildren().values()) {
			Value key = child.getAttribute("consumer key");
			Value secret = child.getAttribute("consumer secret");
			if (key != null && secret != null) {
				TwitterConn tc = new TwitterConn(getMe(), child);
				tc.restoreLastSession();
			} else {
				node.removeChild(child);
			}
		}
	}
	
	private class ConnectHandler implements Handler<ActionResult> {
		public void handle(ActionResult event){
			
			String name = event.getParameter("name", ValueType.STRING).getString();
			String consumerKey = event.getParameter("consumer key", ValueType.STRING).getString();
			String consumerSecret = event.getParameter("consumer secret", ValueType.STRING).getString();
			
			Node cnode = node.createChild(name).build();
			
			cnode.setAttribute("consumer key", new Value(consumerKey));
			cnode.setAttribute("consumer secret", new Value(consumerSecret));
			
			TwitterConn tc = new TwitterConn(getMe(), cnode);
			tc.init();
			
			
			
		}	
	}
	
	public TwitterLink getMe() {
		return this;
	}

}
