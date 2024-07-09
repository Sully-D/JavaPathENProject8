package com.openclassrooms.tourguide.service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import org.springframework.stereotype.Service;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import rewardCentral.RewardCentral;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

/**
 * The RewardsService class provides methods to calculate rewards for users based on their visited locations and attractions.
 * It also includes methods to set and reset the proximity buffer, check if a location is within the proximity range of an attraction,
 * calculate the distance between two locations, and get reward points for a given attraction and user.
 *
 * This class requires a GpsUtil object for GPS related operations and a RewardCentral object for reward calculations.
 */
@Service
public class RewardsService {
	private static final double STATUTE_MILES_PER_NAUTICAL_MILE = 1.15077945;

	// proximity in miles
	private int defaultProximityBuffer = 10;
	private int proximityBuffer = defaultProximityBuffer;
	private int attractionProximityRange = 200;
	private final GpsUtil gpsUtil;
	private final RewardCentral rewardsCentral;

	/**
	 * Constructor for RewardsService class.
	 *
	 * @param gpsUtil the GpsUtil object to be used for GPS related operations
	 * @param rewardCentral the RewardCentral object to be used for reward calculations
	 */
	public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
		this.gpsUtil = gpsUtil;
		this.rewardsCentral = rewardCentral;
	}

	/**
	 * Set the proximity buffer to the specified value.
	 *
	 * @param proximityBuffer the new value for the proximity buffer
	 */
	public void setProximityBuffer(int proximityBuffer) {
		this.proximityBuffer = proximityBuffer;
	}

	/**
	 * Set the proximity buffer back to the default value.
	 */
	public void setDefaultProximityBuffer() {
		proximityBuffer = defaultProximityBuffer;
	}

	/**
	 * Calculate rewards for the given user based on their visited locations and attractions.
	 *
	 * This method creates a thread-safe copy of the user's visited locations and retrieves the list of attractions.
	 * It then iterates over the user's visited locations and for each attraction, checks if the user does not already have a reward for it.
	 * If the user is near the attraction, a new UserReward object is created and added to the user's rewards list.
	 *
	 * @param user the user for whom to calculate rewards
	 */
	public void calculateRewards(User user) {
		// Créer une copie thread-safe de la liste des emplacements visités par l'utilisateur
		CopyOnWriteArrayList<VisitedLocation> userLocations = new CopyOnWriteArrayList<>(user.getVisitedLocations());

		// Récupérer la liste des attractions
		List<Attraction> attractions = gpsUtil.getAttractions();

		// Liste pour stocker les futures des récompenses
		List<CompletableFuture<Void>> futures = new ArrayList<>();

		// Itérer sur les emplacements visités par l'utilisateur
		for (VisitedLocation visitedLocation : userLocations) {
			// Créer une tâche asynchrone pour chaque combinaison d'emplacement visité et d'attraction
			for (Attraction attraction : attractions) {
				CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
					// Vérifier si l'utilisateur n'a pas déjà une récompense pour cette attraction
					if (user.getUserRewards().stream()
							.noneMatch(r -> r.attraction.attractionName.equals(attraction.attractionName))) {
						if (nearAttraction(visitedLocation, attraction)) {
							user.addUserReward(new UserReward(visitedLocation, attraction, getRewardPoints(attraction, user)));
						}
					}
				});
				futures.add(future);
			}
		}

		// Attendre que toutes les tâches soient terminées
		try {
			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
		}
	}
//	public void calculateRewards(User user) {
//		// Récupérer la liste des attractions
//		List<Attraction> attractions = gpsUtil.getAttractions();
//
//		// Liste pour stocker les futures des récompenses
//		List<CompletableFuture<Void>> futures = new ArrayList<>();
//
//		// Itérer sur les emplacements visités par l'utilisateur
//		for (VisitedLocation visitedLocation : user.getVisitedLocations()) {
//			// Créer une tâche asynchrone pour chaque combinaison d'emplacement visité et d'attraction
//			for (Attraction attraction : attractions) {
//				CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
//					// Vérifier si l'utilisateur n'a pas déjà une récompense pour cette attraction
//					if (user.getUserRewards().stream()
//							.noneMatch(r -> r.attraction.attractionName.equals(attraction.attractionName))) {
//						if (nearAttraction(visitedLocation, attraction)) {
//							synchronized (user) {
//								user.addUserReward(new UserReward(visitedLocation, attraction, getRewardPoints(attraction, user)));
//							}
//						}
//					}
//				});
//				futures.add(future);
//			}
//		}
//
//		// Attendre que toutes les tâches soient terminées
//		try {
//			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
//		} catch (InterruptedException | ExecutionException e) {
//			e.printStackTrace();
//		}
//	}




	/**
	 * Check if a location is within the proximity range of an attraction.
	 *
	 * @param attraction the attraction to check proximity to
	 * @param location the location to check against the attraction
	 * @return true if the location is within the attraction's proximity range, false otherwise
	 */
	public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
		return getDistance(attraction, location) > attractionProximityRange ? false : true;
	}

	/**
	 * Check if the visited location is near the given attraction based on the proximity buffer.
	 *
	 * @param visitedLocation the visited location to check proximity from
	 * @param attraction the attraction to check proximity to
	 * @return true if the visited location is near the attraction within the proximity buffer, false otherwise
	 */
	private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
		//return getDistance(attraction, visitedLocation.location) > proximityBuffer ? false : true;
		return !(getDistance(attraction, visitedLocation.location) > proximityBuffer);
	}

	/**
	 * Get the reward points for a given attraction and user.
	 *
	 * @param attraction the attraction for which to get the reward points
	 * @param user the user for whom to get the reward points
	 * @return the reward points for the attraction and user
	 */
	public int getRewardPoints(Attraction attraction, User user) {
		return rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId());
	}

	/**
	 * Calculate the distance in statute miles between two locations.
	 *
	 * @param loc1 the first location
	 * @param loc2 the second location
	 * @return the distance in statute miles between the two locations
	 */
	public double getDistance(Location loc1, Location loc2) {
		double lat1 = Math.toRadians(loc1.latitude);
		double lon1 = Math.toRadians(loc1.longitude);
		double lat2 = Math.toRadians(loc2.latitude);
		double lon2 = Math.toRadians(loc2.longitude);

		double angle = Math.acos(Math.sin(lat1) * Math.sin(lat2)
				+ Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2));

		double nauticalMiles = 60 * Math.toDegrees(angle);
		double statuteMiles = STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;

		return statuteMiles;
	}

}