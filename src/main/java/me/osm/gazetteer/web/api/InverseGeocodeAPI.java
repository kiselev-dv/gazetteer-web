package me.osm.gazetteer.web.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import me.osm.gazetteer.web.ESNodeHolder;
import me.osm.gazetteer.web.api.meta.Endpoint;
import me.osm.gazetteer.web.api.meta.Parameter;
import me.osm.gazetteer.web.api.utils.RequestUtils;
import me.osm.gazetteer.web.imp.IndexHolder;
import me.osm.gazetteer.web.utils.GeometryUtils;

import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.geo.ShapeRelation;
import org.elasticsearch.common.geo.builders.ShapeBuilder;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.FilteredQueryBuilder;
import org.elasticsearch.index.query.GeoDistanceFilterBuilder;
import org.elasticsearch.index.query.GeoShapeFilterBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.TermsFilterBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.sort.SortBuilders;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.restexpress.Request;
import org.restexpress.Response;
import org.restexpress.domain.metadata.UriMetadata;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Point;


/**
 * Inverse geocode API
 * */
public class InverseGeocodeAPI implements DocumentedApi {

	private static final String _NEIGHBOURS = "_neighbours";
	
	private static final String LARGEST_LEVEL_HEADER = "largest_level";
	private static final String MAX_NEIGHBOURS_HEADER = "max_neighbours";
	private static final String RELATED_HEADER = "_related";
	private static final String LAT_HEADER = "lat";
	private static final String LON_HEADER = "lon";
	
	private static final String ALL_LEVEL = "all";
	private static final String OBJECTS_LEVEL = "objects";
	private static final String HIGHWAYS_LEVEL = "highways";
	private static final String PLACE_LEVEL = "places";
	
	/**
	 * REST Express routine read method
	 * 
	 * <ol>
	 * <li> Find enclosed features (poi or address)
	 * <li> Sort them, and take one with smallest geometry area as a main
	 * <li> if related set to true, get related features for main feature 
	 * <li> if there is no enclosed feature were found, find nearest highway 
	 * <li> if there is no highway nearby, return boundaries 
	 * </ol>
	 * 
	 * @param request REST Express request
	 * @param response REST Express response
	 * 
	 * @return JSONObject with following structure
	 * */
	public JSONObject read(Request request, Response response){
		
		JSONObject result = new JSONObject();
		
		// Requested point longitude
		double lon = RequestUtils.getDoubleHeader(LON_HEADER, request);
		
		// Requested point latitude
		double lat = RequestUtils.getDoubleHeader(LAT_HEADER, request);
		
		// Add related objects for founded feature or not
		boolean wRelated = request.getHeader(RELATED_HEADER) != null;
		
		// Store full geometry of objects or not
		boolean fullGeometry = request.getHeader(SearchAPI.FULL_GEOMETRY_HEADER) != null 
				&& "true".equals(request.getParameter(SearchAPI.FULL_GEOMETRY_HEADER));
		
		// No more than this amount of neighbours please 
		int maxNeighbours = request.getHeader(MAX_NEIGHBOURS_HEADER) == null ? 15 : 
			Integer.valueOf(request.getHeader(MAX_NEIGHBOURS_HEADER));
		
		AnswerDetalization detalization = RequestUtils.getEnumHeader(request, 
				SearchAPI.ANSWER_DETALIZATION_HEADER, AnswerDetalization.class, AnswerDetalization.FULL);
		
		/* How large objects what we are looking for?
		 * 
		 * OBJECTS_LEVEL    try to find enclosing poipnt or adrpoint only
		 * 
		 * HIGHWAYS_LEVEL   if there is no enclosing OBJECTS_LEVEL features,
		 *                  try to find highway
		 *                 
		 * ALL_LEVEL        if there is no highways, return, at least all
		 *                  enclosing boundaries
		 *                 
		 * PLACE_LEVEL      Search for places (without streets)               
		 *                 
		 * Why not to include all enclosing boundaries always by default?
		 * All objects already have all enclosing boundaries                               
		 * 
		 */
		String largestLevel = request.getHeader(LARGEST_LEVEL_HEADER) == null ? 
				HIGHWAYS_LEVEL : request.getHeader(LARGEST_LEVEL_HEADER);

		if(maxNeighbours > 100) {
			maxNeighbours = 100;
		}
		
		if(maxNeighbours < 0) {
			maxNeighbours = 0;
		}
		
		if(PLACE_LEVEL.equals(largestLevel)) {
			fillBoundaries(result, lon, lat, fullGeometry, 
					new ArrayList<JSONObject>(), new LinkedHashMap<String, String>());
			
	 		return detalization(result, detalization);
		}
		
		List<JSONObject> neighbours = maxNeighbours == 0 ? null : new ArrayList<JSONObject>(maxNeighbours);
		List<JSONObject> enclosedFeatures = getEnclosedFeatures(lon, lat, maxNeighbours, neighbours);

		// Get first feature as a main feature.
		JSONObject mainFeature = enclosedFeatures.isEmpty() ? null : enclosedFeatures.remove(0);
		
		// If main feature is founded, write it out
		if(mainFeature != null) {
			return writeMainFeature(wRelated, fullGeometry, neighbours,
					enclosedFeatures, mainFeature);
		}
		
		// Return neighbours only
		if(largestLevel.equals(OBJECTS_LEVEL)) {
			result.put(_NEIGHBOURS, neighbours);
			return detalization(result, detalization);
		}

		// If there is no enclosing features, look for highways within 25 meters
		JSONObject highway = getHighway(lon, lat, 25);

		// Address parts to return 
		LinkedHashMap<String, String> parts = new LinkedHashMap<String, String>();
		if(highway != null) {
			
			// Fill address parts by founded highway
			fillByHighway(parts, highway);
			if (!fullGeometry) {
				highway.remove("full_geometry");
			}
			
			result.put("highway", highway);
			result.put("parts", new JSONObject(parts));
			result.put("text", StringUtils.join(parts.values(), ", "));
			
			// Don't forget about neighbours
			result.put(_NEIGHBOURS, neighbours);
			
			return detalization(result, detalization);
		}
		else if(largestLevel.equals(HIGHWAYS_LEVEL)) {
			// Don't forget about neighbours
			result.put(_NEIGHBOURS, neighbours);
			return detalization(result, detalization);
		}
		
		fillBoundaries(result, lon, lat, fullGeometry, neighbours, parts);
		
		return detalization(result, detalization);
	}

	private JSONObject detalization(JSONObject raw,
			AnswerDetalization detalization) {
		if (detalization == AnswerDetalization.FULL) {
			return raw;
		}
		else if(detalization == AnswerDetalization.SHORT) {
			JSONObject shortAnswer = shortAnswer(raw);
			
			if(raw.has("_related")) {
				JSONObject shortRelated = new JSONObject();
				JSONObject related = raw.getJSONObject("_related");
				
				// Split namespaces not to mess with keys
				{
					JSONArray sameType = related.optJSONArray("_same_type");
					if(sameType != null) {
						JSONArray shortSameType = new JSONArray();
						for(int i = 0; i < sameType.length(); i++){
							shortSameType.put(shortAnswer(sameType.getJSONObject(i)));
						}
						shortRelated.put("_same_type", shortSameType);
					}
				}
				// Split namespaces
				{
					JSONArray sameBuilding = related.optJSONArray("_same_building");
					if(sameBuilding != null) {
						JSONArray shortSameBuilding = new JSONArray();
						for(int i = 0; i < sameBuilding.length(); i++){
							shortSameBuilding.put(shortAnswer(sameBuilding.getJSONObject(i)));
						}
						shortRelated.put("_same_building", shortSameBuilding);
					}
				}
				
				shortAnswer.put("_related", shortRelated);
			}

			if(raw.has("_neighbours")) {
				JSONArray shortNeighbours = new JSONArray();
				JSONArray neighbours = raw.getJSONArray("_neighbours");
				for(int i = 0; i < neighbours.length(); i++) {
					shortNeighbours.put(shortAnswer(neighbours.getJSONObject(i)));
				}
				shortAnswer.put("_neighbours", shortNeighbours);
			}
			
			return shortAnswer;
		}
		return raw;
	}

	private JSONObject shortAnswer(JSONObject raw) {
		JSONObject shortResult = new JSONObject(raw, 
				new String[]{"id", "type", "name", "center_point"});
		shortResult.put("address", getAddressText(raw));
		return shortResult;
	}
	
	private static String getAddressText(JSONObject source) {
		try {
			return source.getJSONObject("address").getString("text");
		}
		catch (JSONException e) {
			return null;
		}
	}

	private void fillBoundaries(JSONObject result, double lon, double lat,
			boolean fullGeometry, List<JSONObject> neighbours,
			LinkedHashMap<String, String> parts) {
		// Get administrative boundaries 
		Map<String, JSONObject> levels = getBoundariesLevels(lon, lat);
		
		// Fill address parts by founded boundaries
		fillByBoundaries(fullGeometry, parts, levels);
		result.put("boundaries", new JSONObject(levels));
		
		result.put("text", StringUtils.join(parts.values(), ", "));
		result.put("parts", new JSONObject(parts));
		
		// Don't forget about neighbours 
		result.put(_NEIGHBOURS, neighbours);
	}

	/**
	 * Write out founded objects
	 * 
	 * @param find and write related objects {@link FeatureAPI#getRelated}
	 * @param fullGeometry store full geometry for related and neighbour objects
	 * @param neighbours list of neighbours
	 * @param enclosedFeatures list of objects encloses provided point
	 * @param mainFeature most relevant feature
	 * 
	 * @return result encoded as JSONObject
	 * */
	private JSONObject writeMainFeature(boolean wRelated, boolean fullGeometry,
			List<JSONObject> neighbours, List<JSONObject> enclosedFeatures,
			JSONObject mainFeature) {
		
		//Remove full geometry for neighbours
		if(!fullGeometry && neighbours != null) {
			for(JSONObject n : neighbours) {
				n.remove("full_geometry");
			}
		}

		if(wRelated) {
			JSONObject related = FeatureAPI.getRelated(mainFeature);
			if(related != null) {
				mainFeature.put(RELATED_HEADER, related);
			}
		}

		if(neighbours != null) {
			mainFeature.put("_neighbours", neighbours);
		}
		
		if(!enclosedFeatures.isEmpty()) {
			mainFeature.put("_enclosed", enclosedFeatures);
		}
		
		return mainFeature;
	}

	/**
	 * Search for highway with r meters around
	 * 
	 * @param lon center longitude
	 * @param lat center latitude
	 * @param r radius in meters
	 * 
	 * @return founded highway or null
	 * */
	public JSONObject getHighway(double lon, double lat, int r) {
		Client client = ESNodeHolder.getClient();
		
		FilteredQueryBuilder q =
				QueryBuilders.filteredQuery(
						QueryBuilders.matchAllQuery(),
						FilterBuilders.andFilter(
								FilterBuilders.termsFilter("type", "hghway", "hghnet"),
								FilterBuilders.geoShapeFilter("full_geometry", 
										ShapeBuilder.newCircleBuilder().center(lon, lat)
											.radius(r, DistanceUnit.METERS), ShapeRelation.INTERSECTS)
						));
		
		SearchRequestBuilder searchRequest = 
				client.prepareSearch("gazetteer").setTypes(IndexHolder.LOCATION).setQuery(q);
		
		searchRequest.setSize(1);
		SearchResponse searchResponse = searchRequest.get();
		
		SearchHit[] hits = searchResponse.getHits().getHits();
		for(SearchHit hit : hits) {
			return new JSONObject(hit.getSource());
		}
		
		return null;
	}

	/**
	 * Find features, which encloses provided point
	 * 
	 * @param lon longitude
	 * @param lat latitude
	 * @param maxNeighbours maximum amount of neighbour objects
	 * @param neighbours where to put neighbour objects
	 * 
	 * @return enclosed features
	 * */
	public List<JSONObject> getEnclosedFeatures(double lon, double lat, int maxNeighbours, List<JSONObject> neighbours) {
		
		List<JSONObject> result = new ArrayList<>();
		
		SearchRequestBuilder searchRequest = 
				buildEnclosedFeaturesRequest(lon, lat, maxNeighbours);
		
		SearchResponse searchResponse = searchRequest.get();
				
		SearchHit[] hits = searchResponse.getHits().getHits();
		
		Point p = GeometryUtils.factory.createPoint(new Coordinate(lon, lat));
		for(SearchHit hit : hits) {
			JSONObject feature = new JSONObject(hit.getSource());
			Geometry geoemtry = GeometryUtils.parseGeometry(feature.optJSONObject("full_geometry"));
			if (geoemtry != null && geoemtry.contains(p)) {
				
				// save geometry area for futher sorting
				// just not to parse geometry twice
				feature.put("_geometry_area", geoemtry.getArea());
				result.add(feature);
			}
			else if(neighbours != null) {
				
				// Neighbours already sorted by distance from lon,lat by ES
				neighbours.add(feature);
			}
		}

		// This sorting is for the case, when we have building inside POI
		// In such case we assume that building is more important
		Collections.sort(result, new Comparator<JSONObject>(){

			@Override
			public int compare(JSONObject o1, JSONObject o2) {
				return Double.compare(o1.getDouble("_geometry_area"), o2.getDouble("_geometry_area"));
			}
			
		});
		
		return FeatureAPI.mergeFeaturesByID(result);
	}

	/**
	 * Build request for getting features encloses provided lon and lat
	 * 
	 *  @param lon longitude
	 *  @param lat latitude
	 *  @param maxNeighbours how many objects should we check
	 *  
	 *  @return ElasticSearch SearchRequestBuilder
	 * */
	private SearchRequestBuilder buildEnclosedFeaturesRequest(double lon,
			double lat, int maxNeighbours) {
		
		Client client = ESNodeHolder.getClient();
		
		FilteredQueryBuilder q =
				QueryBuilders.filteredQuery(
						QueryBuilders.matchAllQuery(),
						FilterBuilders.andFilter(
								FilterBuilders.termsFilter("type", "adrpnt", "poipnt"),
								FilterBuilders.geoDistanceFilter("center_point").point(lat, lon)
									.distance(1000, DistanceUnit.METERS)
						));

		SearchRequestBuilder searchRequest = client.prepareSearch("gazetteer")
				.setTypes(IndexHolder.LOCATION).setQuery(q);
		
		searchRequest.addSort(SortBuilders.geoDistanceSort("center_point").point(lat, lon));
		
		searchRequest.setSize(maxNeighbours == 0 ? 10 : maxNeighbours);
		return searchRequest;
	}

	/**
	 * Get all administrative boundaries encloses provided point
	 * 
	 * @param lon center longitude
	 * @param lat center latitude
	 * 
	 * @return boundaries mapped by it's levels (addr_level attribute value)
	 * */
	public Map<String, JSONObject> getBoundariesLevels(double lon, double lat) {
		Client client = ESNodeHolder.getClient();
		
		GeoShapeFilterBuilder filter = FilterBuilders.geoShapeFilter("full_geometry", 
				ShapeBuilder.newPoint(lon, lat), ShapeRelation.INTERSECTS);
		
		FilteredQueryBuilder q =
				QueryBuilders.filteredQuery(
						QueryBuilders.matchAllQuery(),
						filter);
		
		SearchRequestBuilder searchRequest = client.prepareSearch("gazetteer")
				.setTypes(IndexHolder.LOCATION).setQuery(q);
		
		SearchResponse searchResponse = searchRequest.get();
				
		SearchHit[] hits = searchResponse.getHits().getHits();
		List<JSONObject> boundaries = new ArrayList<JSONObject>();
		
		Map<String, JSONObject> levels = new HashMap<String, JSONObject>();
		for(SearchHit hit : hits) {
			JSONObject obj = new JSONObject(hit.getSourceAsString());

			boundaries.add(obj);
			levels.put(obj.optString("addr_level"), obj);
		}
		
		if (!levels.containsKey("locality")) {
			
			GeoDistanceFilterBuilder distanceF = FilterBuilders.geoDistanceFilter("center_point")
					.distance("1km").lon(lon).lat(lat);
			
			FilterBuilder plcpnt = FilterBuilders.termFilter("type", "plcpnt");
			
			FilteredQueryBuilder hamlets =
					QueryBuilders.filteredQuery(
							QueryBuilders.matchAllQuery(),
							FilterBuilders.andFilter()
								.add(distanceF)
								.add(plcpnt));
			
			searchRequest = client.prepareSearch("gazetteer")
					.setTypes(IndexHolder.LOCATION).setQuery(hamlets);
			searchRequest.addSort(SortBuilders.geoDistanceSort("center_point").point(lat, lon));
			searchRequest.setSize(1);
			
			searchResponse = searchRequest.get();
			hits = searchResponse.getHits().getHits();
			
			if(hits.length > 0) {
				JSONObject obj = new JSONObject(hits[0].getSourceAsString());

				boundaries.add(obj);
				levels.put(obj.optString("addr_level"), obj);
			}
			
		}
		
		return levels;
	}
	
	/**
	 * Fill address parts by highway
	 * 
	 * @param parts target parts map
	 * @param highway source of information
	 * */
	private void fillByHighway(LinkedHashMap<String, String> parts, JSONObject highway) {
		if(highway.has("admin0_name")) {
			parts.put("admin0", highway.optString("admin0_name"));
		}
		if(highway.has("admin1_name")) {
			parts.put("admin1", highway.optString("admin1_name"));
		}
		if(highway.has("admin2_name")) {
			parts.put("admin2", highway.optString("admin2_name"));
		}
		if(highway.has("local_admin_name")) {
			parts.put("local_admin", highway.optString("local_admin_name"));
		}
		if(highway.has("locality_name")) {
			parts.put("locality", highway.optString("locality_name"));
		}
		else if(highway.has("nearest_place")) {
			parts.put("locality", highway.getJSONObject("nearest_place").optString("name"));
		}
		if(highway.has("neighborhood_name")) {
			parts.put("neighborhood", highway.optString("neighborhood_name"));
		}
		else if(highway.has("nearest_neighborhood")) {
			parts.put("neighborhood", highway.getJSONObject("nearest_neighborhood").optString("name"));
		}
		if(highway.has("street_name")) {
			parts.put("street", highway.optString("street_name"));
		}
		if(highway.has("housenumber")) {
			parts.put("housenumber", highway.optString("housenumber"));
		}
	}

	/**
	 * Fill address parts by boundaries
	 * 
	 * @param fullGeometry keep full geometry
	 * @param parts target parts map
	 * @param boundaries boundaries mapped by level
	 * */
	private void fillByBoundaries(boolean fullGeometry, LinkedHashMap<String, String> parts,
			Map<String, JSONObject> boundaries) {
		
		if(boundaries.containsKey("admin0")) {
			parts.put("admin0", boundaries.get("admin0").optString("name"));
		}
		if(boundaries.containsKey("admin1")) {
			parts.put("admin1", boundaries.get("admin1").optString("name"));
		}
		if(boundaries.containsKey("admin2")) {
			parts.put("admin2", boundaries.get("admin2").optString("name"));
		}
		if(boundaries.containsKey("local_admin")) {
			parts.put("local_admin", boundaries.get("local_admin").optString("name"));
		}
		if(boundaries.containsKey("locality")) {
			parts.put("locality", boundaries.get("locality").optString("name"));
		}
		if(boundaries.containsKey("neighborhood")) {
			parts.put("neighborhood", boundaries.get("neighborhood").optString("name"));
		}
		
		if (!fullGeometry) {
			for(Entry<String, JSONObject> entry : boundaries.entrySet()) {
				entry.getValue().remove("full_geometry");
			}
		}
	}

	@Override
	public Endpoint getMeta(UriMetadata uriMetadata) {
		Endpoint meta = new Endpoint(uriMetadata.getPattern(), "Inverse geocode", 
				"Finds address or object by given coorinates.");
		
		meta.getPathParameters().add(new Parameter(LAT_HEADER, "latitude"));
		meta.getPathParameters().add(new Parameter(LON_HEADER, "longitude"));
		meta.getPathParameters().add(new Parameter(RELATED_HEADER, "Also return related objects."));
		
		meta.getUrlParameters().add(new Parameter(LAT_HEADER, 
				"latitude maybe specified thrue path or via get parameters"));
		meta.getUrlParameters().add(new Parameter(LON_HEADER, 
				"longitude maybe specified thrue path or via get parameters"));
		meta.getUrlParameters().add(new Parameter(LARGEST_LEVEL_HEADER, 
				OBJECTS_LEVEL + " try to find enclosing poipnt or adrpoint only. " +
				HIGHWAYS_LEVEL + " if there is no enclosing OBJECTS_LEVEL features, try to find highway. " + 
				ALL_LEVEL + "if there is no highways, return, at least all enclosing boundaries. "
			 + "Default level is " + HIGHWAYS_LEVEL));
		meta.getUrlParameters().add(new Parameter(MAX_NEIGHBOURS_HEADER, 
				"This API returns enclosed feature and this amount of nearby features. "
			  + "Default amount is 15. Use 0 turn neighbours search off. "
			  + "Maximum avaible number is 100."));
		
		return meta;
	}
	
}
