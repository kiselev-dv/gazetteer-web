package me.osm.gazetteer.web.api.renders;

import me.osm.gazetteer.web.Configuration;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;

public class HTMLSitemapRender extends ASitemapRender {

	public HTMLSitemapRender(Configuration config) {
		super(config);
	}


	@Override
	public void feature(String id, JSONObject obj) {
		String featureURL = StringUtils.replace(featureUrlTemplate, "{id}", id);
		featureURL = hostName + featureURL;
		
		sb.append("    <li><a href=\"/#!/ru/id/" + id + "/details\">" + id + "</a></li>\n");
	}
	
	@Override
	public void page(int page) {
		String url = "/!#/sitemap?index_page=" + page;
		sb.append("    <li><a href=\"" + url + "\">" + url + "</a></li>\n");
	}

	@Override
	public void pageEnd() {
		super.pageEnd();
		bodyClose();
	}
	
	@Override
	public void indexBegin() {
		super.indexBegin();
		bodyOpen();
	}
	
	@Override
	public void indexEnd() {
		super.indexEnd();
		bodyClose();
	}

	private void bodyOpen() {
		sb.append("<html>\n");
		sb.append("<body>\n");
		sb.append("<ul>\n");
	}

	private void bodyClose() {
		sb.append("</ul>\n");
		sb.append("</body>\n");
		sb.append("</html>");
	}

	@Override
	public void pageBegin() {
		super.pageBegin();
		bodyOpen();
	}
}
