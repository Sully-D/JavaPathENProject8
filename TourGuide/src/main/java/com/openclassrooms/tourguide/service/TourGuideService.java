package com.openclassrooms.tourguide.service;

import com.openclassrooms.tourguide.helper.InternalTestHelper;
import com.openclassrooms.tourguide.tracker.Tracker;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;

import rewardCentral.RewardCentral;
import tripPricer.Provider;
import tripPricer.TripPricer;

/**
 * The TourGuideService class provides methods to manage users, track user locations, calculate rewards,
 * retrieve trip deals, and find nearby attractions. It interacts with GpsUtil for GPS related operations,
 * RewardsService for reward calculations, and TripPricer for retrieving trip deals.
 *
 * This class includes methods to retrieve user rewards, user location, user by username, all users,
 * add a new user, track user location, get nearby attractions, and get trip deals for a user.
 *
 * It also initializes internal test users for testing purposes and provides methods for generating user location history.
 *
 * The class is designed to handle internal users stored in memory and provides a shutdown hook for stopping the tracker.
 */
@Service
public class TourGuideService {
	private Logger logger = LoggerFactory.getLogger(TourGuideService.class);
	private final GpsUtil gpsUtil;
	private final RewardsService rewardsService;
	private final TripPricer tripPricer = new TripPricer();
	public final Tracker tracker;
	private final ExecutorService executorService = Executors.newFixedThreadPool(100);
	boolean testMode = true;

	/**
	 * Constructor for TourGuideService class.
	 *
	 * @param gpsUtil the GpsUtil object to be used for GPS related operations
	 * @param rewardsService the RewardsService object to be used for reward calculations
	 */
	public TourGuideService(GpsUtil gpsUtil, RewardsService rewardsService) {
		this.gpsUtil = gpsUtil;
		this.rewardsService = rewardsService;

		Locale.setDefault(Locale.US);

		if (testMode) {
			logger.info("TestMode enabled");
			logger.debug("Initializing users");
			initializeInternalUsers();
			logger.debug("Finished initializing users");
		}
		tracker = new Tracker(this);
		addShutDownHook();
	}

	/**
	 * Retrieves the list of rewards associated with the specified User.
	 *
	 * @param user the User object for which to retrieve the rewards
	 * @return a list of UserReward objects representing the rewards for the specified User
	 */
	public List<UserReward> getUserRewards(User user) {
		return user.getUserRewards();
	}

	/**
	 * Retrieves the visited location of the specified User. If the User has visited locations recorded,
	 * the method returns the last visited location. Otherwise, it tracks the User's current location
	 * using the trackUserLocation method and returns the visited location.
	 *
	 * @param user the User object for which to retrieve the visited location
	 * @return the VisitedLocation object representing the User's visited location
	 */
	public CompletableFuture<VisitedLocation> getUserLocation(User user) {
		if (user.getVisitedLocations().size() > 0) {
			return CompletableFuture.completedFuture(user.getLastVisitedLocation());
		} else {
			return trackUserLocation(user);
		}
	}


	/**
	 * Retrieves the User object associated with the specified userName.
	 *
	 * @param userName the username of the User to retrieve
	 * @return the User object corresponding to the specified userName, or null if not found
	 */
	public User getUser(String userName) {
		return internalUserMap.get(userName);
	}

	/**
	 * Retrieves all the users stored in the internalUserMap and returns them as a list.
	 *
	 * @return a list of User objects representing all the users stored in the internalUserMap
	 */
	public List<User> getAllUsers() {
		return new ArrayList<>(internalUserMap.values());
	}

	/**
	 * Adds a new User to the internalUserMap if the user's username is not already present.
	 *
	 * @param user the User object to be added
	 */
	public void addUser(User user) {
		if (!internalUserMap.containsKey(user.getUserName())) {
			internalUserMap.put(user.getUserName(), user);
		}
	}

	/**
	 * Retrieves a list of trip deals for the specified User based on their preferences and rewards.
	 *
	 * @param user the User object for which to retrieve trip deals
	 * @return a list of Provider objects representing the available trip deals for the User
	 */
	public List<Provider> getTripDeals(User user) {
		int cumulatativeRewardPoints = user.getUserRewards().stream().mapToInt(i -> i.getRewardPoints()).sum();
		List<Provider> providers = tripPricer.getPrice(tripPricerApiKey, user.getUserId(),
				user.getUserPreferences().getNumberOfAdults(), user.getUserPreferences().getNumberOfChildren(),
				user.getUserPreferences().getTripDuration(), cumulatativeRewardPoints);
		user.setTripDeals(providers);
		return providers;
	}

	/**
	 * Retrieves the current location of the specified User using the GpsUtil service.
	 * Adds the visited location to the User's visited locations list and calculates rewards for the User based on the visited location.
	 *
	 * @param user the User object for which to track the location
	 * @return the VisitedLocation object representing the User's current location
	 */
//	public VisitedLocation trackUserLocation(User user) {
//		VisitedLocation visitedLocation = gpsUtil.getUserLocation(user.getUserId());
//		user.addToVisitedLocations(visitedLocation);
//		rewardsService.calculateRewards(user);
//		return visitedLocation;
//	}
	public CompletableFuture<VisitedLocation> trackUserLocation(User user) {
		return CompletableFuture.supplyAsync(() -> {
			VisitedLocation visitedLocation = gpsUtil.getUserLocation(user.getUserId());
			user.addToVisitedLocations(visitedLocation);
			// Attendre que calculateRewards soit terminé
			rewardsService.calculateRewards(user).join();
			return visitedLocation;
		}, executorService);
	}


	public void shutdown() {
		executorService.shutdown();
	}

	/**
	 * Retrieves a list of nearby attractions based on the visited location.
	 *
	 * This method calculates the distance between the visited location and all attractions using the RewardsService.
	 * It then sorts the attractions based on distance and returns information about the five closest attractions.
	 * For each nearby attraction, details such as name, latitude, longitude, user's location, distance from user, and reward points are included in the result.
	 *
	 * @param visitedLocation the VisitedLocation object representing the user's current location
	 * @return a list of strings containing information about the five closest attractions to the visited location
	 */
	public CompletableFuture<List<String>> getNearByAttractions(CompletableFuture<VisitedLocation> visitedLocationFuture) {
		return visitedLocationFuture.thenApply(visitedLocation -> {
			Map<Attraction, Double> distanceAttractions = new HashMap<>();
			List<String> nearbyAttractions = new ArrayList<>();
			for (Attraction attraction : gpsUtil.getAttractions()) {
				double distance = rewardsService.getDistance(attraction, visitedLocation.location);
				distanceAttractions.put(attraction, distance);
			}

			List<Map.Entry<Attraction, Double>> convertirDistanceAttractionsToList = new ArrayList<>(distanceAttractions.entrySet());

			convertirDistanceAttractionsToList.sort(Map.Entry.comparingByValue());

			List<Map.Entry<Attraction, Double>> smallestFive = convertirDistanceAttractionsToList
					.subList(0, Math.min(5, convertirDistanceAttractionsToList.size()));

			for (Map.Entry<Attraction, Double> attraction : smallestFive) {
				List<String> temp = new ArrayList<>();
				User tempUser = new User(visitedLocation.userId, "temps", "temp", "temp");
				temp.add("Attraction name : " + attraction.getKey().attractionName);
				temp.add("Attraction latitude : " + String.valueOf(attraction.getKey().latitude)
						+ " Attraction longitude : " + String.valueOf(attraction.getKey().longitude));
				temp.add("User latitude : " + visitedLocation.location.latitude
						+ " User longitude : " + visitedLocation.location.longitude);
				temp.add("Distance user / attraction : " + attraction.getValue());
				temp.add("Reward points : " + rewardsService.getRewardPoints(attraction.getKey(), tempUser));
				nearbyAttractions.add(String.valueOf(temp));
			}

			return nearbyAttractions;
		});
	}


	private void addShutDownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				tracker.stopTracking();
			}
		});
	}

	/**********************************************************************************
	 * 
	 * Methods Below: For Internal Testing
	 * 
	 **********************************************************************************/
	private static final String tripPricerApiKey = "test-server-api-key";
	// Database connection will be used for external users, but for testing purposes
	// internal users are provided and stored in memory
	private final Map<String, User> internalUserMap = new HashMap<>();

	private void initializeInternalUsers() {
		IntStream.range(0, InternalTestHelper.getInternalUserNumber()).forEach(i -> {
			String userName = "internalUser" + i;
			String phone = "000";
			String email = userName + "@tourGuide.com";
			User user = new User(UUID.randomUUID(), userName, phone, email);
			generateUserLocationHistory(user);

			internalUserMap.put(userName, user);
		});
		logger.debug("Created " + InternalTestHelper.getInternalUserNumber() + " internal test users.");
	}

	private void generateUserLocationHistory(User user) {
		IntStream.range(0, 3).forEach(i -> {
			user.addToVisitedLocations(new VisitedLocation(user.getUserId(),
					new Location(generateRandomLatitude(), generateRandomLongitude()), getRandomTime()));
		});
	}

	private double generateRandomLongitude() {
		double leftLimit = -180;
		double rightLimit = 180;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	private double generateRandomLatitude() {
		double leftLimit = -85.05112878;
		double rightLimit = 85.05112878;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	private Date getRandomTime() {
		LocalDateTime localDateTime = LocalDateTime.now().minusDays(new Random().nextInt(30));
		return Date.from(localDateTime.toInstant(ZoneOffset.UTC));
	}

}
