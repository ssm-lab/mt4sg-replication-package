package ca.mcmaster.mt4sg.evaluation

import ca.mcmaster.mt4sg.models.scenegraph.SemanticDistanceValue
import ca.mcmaster.mt4sg.models.scenegraph.Edge
import ca.mcmaster.mt4sg.models.scenegraph.SceneGraph
import ca.mcmaster.mt4sg.models.scenegraph.SceneGraphModelPackage
import ca.mcmaster.mt4sg.models.scenegraph.SemanticSpatialPositionValue
import ca.mcmaster.mt4sg.models.scenegraph.Vehicle
import ca.mcmaster.mt4sg.models.scenegraph.LaneSegment
import ca.mcmaster.mt4sg.models.scenegraph.Node
import ca.mcmaster.mt4sg.queries.Scenegraph
import java.util.concurrent.Callable
import org.apache.log4j.Logger
import org.eclipse.emf.ecore.EClass
import org.eclipse.emf.ecore.resource.Resource
import org.eclipse.emf.transaction.RecordingCommand
import org.eclipse.emf.transaction.TransactionalEditingDomain
import org.eclipse.viatra.query.runtime.api.AdvancedViatraQueryEngine
import org.eclipse.viatra.query.runtime.emf.EMFScope
import org.eclipse.viatra.transformation.runtime.emf.modelmanipulation.IModelManipulations
import org.eclipse.viatra.transformation.runtime.emf.modelmanipulation.SimpleModelManipulations
import org.eclipse.viatra.transformation.runtime.emf.rules.BatchTransformationRuleGroup
import org.eclipse.viatra.transformation.runtime.emf.rules.batch.BatchTransformationRuleFactory
import org.eclipse.viatra.transformation.runtime.emf.transformation.batch.BatchTransformation

/**
 * The rules of {@link ca.mcmaster.mt4sg.core.runner.Transformations} as a VIATRA batch
 * transformation, for the evaluation's batch strategies. The preconditions, actions, and model
 * manipulation helpers are copied from that class unchanged. The transformation runs on a query
 * engine of its own, which keeps the query results up to date with the model for as long as the
 * transformation lives, and
 * its rules fire only in {@link #fireWhilePossible}.
 */
class BatchTransformations {

	extension Logger logger = Logger.getLogger(BatchTransformations)

	extension BatchTransformationRuleFactory ruleFactory = new BatchTransformationRuleFactory
	extension IModelManipulations manipulation
	extension Scenegraph scenegraph = Scenegraph::instance

	val AdvancedViatraQueryEngine engine
	val BatchTransformation transformation
	val TransactionalEditingDomain domain

	new(Resource resource) {
		engine = AdvancedViatraQueryEngine.createUnmanagedEngine(new EMFScope(resource))
		domain = TransactionalEditingDomain.Factory.INSTANCE.getEditingDomain(resource.resourceSet)
		manipulation = new SimpleModelManipulations(engine)
		transformation = BatchTransformation.forEngine(engine).addRules(rules).build
	}

	/** Runs {@code runnable} with the query result updates held back until it returns. */
	def void delayUpdatePropagation(Runnable runnable) {
		engine.delayUpdatePropagation(new Callable<Object>() {
			override call() throws Exception {
				runnable.run()
				return null
			}
		})
	}

	/** Fires the rules, 1 activation at a time, until none is enabled. */
	def void fireWhilePossible() {
		domain.commandStack.execute(new RecordingCommand(domain) {
			override protected doExecute() {
				transformation.transformationStatements.fireWhilePossible
			}
		})
	}

	def getRules() {
		val rules = new BatchTransformationRuleGroup(
			createPreCollisionRule,
			createSuperNearRule,
			createVeryNearRule,
			createNearRule,
			createVisibleDistanceRule,
			updatePreCollisionRule,
			updateSuperNearRule,
			updateVeryNearRule,
			updateNearRule,
			updateVisibleDistanceRule,
			removeDistanceEdgeRule,
			createLeftRule,
			createFrontLeftRule,
			createFrontRule,
			createFrontRightRule,
			createRightRule,
			createRearRightRule,
			createRearRule,
			createRearLeftRule,
			updateLeftRule,
			updateFrontLeftRule,
			updateFrontRule,
			updateFrontRightRule,
			updateRightRule,
			updateRearRightRule,
			updateRearRule,
			updateRearLeftRule,
			createVehicleOnLaneSegmentRule,
			removeVehicleOnLaneSegmentRule,
			createLongitudinalRSSViolationRule,
			removeLongitudinalRSSViolationRule,
			createLateralRSSViolationRule,
			removeLateralRSSViolationRule
		)

		return rules
	}

	// Distance (proximity) edges

	val createPreCollisionRule = createRule(preCollisionEdgeToBeAdded).name("CreatePreCollisionRule").action [
		createDistanceEdge(v1, v2, SemanticDistanceValue.PRE_COLLISION)
	].build

	val createSuperNearRule = createRule(superNearEdgeToBeAdded).name("CreateSuperNearRule").action [
		createDistanceEdge(v1, v2, SemanticDistanceValue.SUPER_NEAR)
	].build

	val createVeryNearRule = createRule(veryNearEdgeToBeAdded).name("CreateVeryNearRule").action [
		createDistanceEdge(v1, v2, SemanticDistanceValue.VERY_NEAR)
	].build

	val createNearRule = createRule(nearEdgeToBeAdded).name("CreateNearRule").action [
		createDistanceEdge(v1, v2, SemanticDistanceValue.NEAR)
	].build

	val createVisibleDistanceRule = createRule(visibleDistanceEdgeToBeAdded).name("CreateVisibleDistanceRule").action [
		createDistanceEdge(v1, v2, SemanticDistanceValue.VISIBLE)
	].build

	val updatePreCollisionRule = createRule(updatePreCollision).name("UpdatePreCollisionRule").action [
		updateDistance(e, SemanticDistanceValue.PRE_COLLISION)
	].build

	val updateSuperNearRule = createRule(updateSuperNear).name("UpdateSuperNearRule").action [
		updateDistance(e, SemanticDistanceValue.SUPER_NEAR)
	].build

	val updateVeryNearRule = createRule(updateVeryNear).name("UpdateVeryNearRule").action [
		updateDistance(e, SemanticDistanceValue.VERY_NEAR)
	].build

	val updateNearRule = createRule(updateNear).name("UpdateNearRule").action [
		updateDistance(e, SemanticDistanceValue.NEAR)
	].build

	val updateVisibleDistanceRule = createRule(updateVisibleDistance).name("UpdateVisibleDistanceRule").action [
		updateDistance(e, SemanticDistanceValue.VISIBLE)
	].build

	val removeDistanceEdgeRule = createRule(distanceEdgeToBeRemoved).name("RemoveEdgeRule").action [
		deleteEdge(e)
	].build

	// Spatial (8-sector bearing) edges

	val createLeftRule = createRule(leftEdgeToBeAdded).name("CreateLeftRule").action [
		createSpatialEdge(source, target, SemanticSpatialPositionValue.LEFT)
	].build

	val createFrontLeftRule = createRule(frontLeftEdgeToBeAdded).name("CreateFrontLeftRule").action [
		createSpatialEdge(source, target, SemanticSpatialPositionValue.FRONT_LEFT)
	].build

	val createFrontRule = createRule(frontEdgeToBeAdded).name("CreateFrontRule").action [
		createSpatialEdge(source, target, SemanticSpatialPositionValue.FRONT)
	].build

	val createFrontRightRule = createRule(frontRightEdgeToBeAdded).name("CreateFrontRightRule").action [
		createSpatialEdge(source, target, SemanticSpatialPositionValue.FRONT_RIGHT)
	].build

	val createRightRule = createRule(rightEdgeToBeAdded).name("CreateRightRule").action [
		createSpatialEdge(source, target, SemanticSpatialPositionValue.RIGHT)
	].build

	val createRearRightRule = createRule(rearRightEdgeToBeAdded).name("CreateRearRightRule").action [
		createSpatialEdge(source, target, SemanticSpatialPositionValue.REAR_RIGHT)
	].build

	val createRearRule = createRule(rearEdgeToBeAdded).name("CreateRearRule").action [
		createSpatialEdge(source, target, SemanticSpatialPositionValue.REAR)
	].build

	val createRearLeftRule = createRule(rearLeftEdgeToBeAdded).name("CreateRearLeftRule").action [
		createSpatialEdge(source, target, SemanticSpatialPositionValue.REAR_LEFT)
	].build

	val updateLeftRule = createRule(updateLeft).name("UpdateLeftRule").action [
		updateSpatial(e, SemanticSpatialPositionValue.LEFT)
	].build

	val updateFrontLeftRule = createRule(updateFrontLeft).name("UpdateFrontLeftRule").action [
		updateSpatial(e, SemanticSpatialPositionValue.FRONT_LEFT)
	].build

	val updateFrontRule = createRule(updateFront).name("UpdateFrontRule").action [
		updateSpatial(e, SemanticSpatialPositionValue.FRONT)
	].build

	val updateFrontRightRule = createRule(updateFrontRight).name("UpdateFrontRightRule").action [
		updateSpatial(e, SemanticSpatialPositionValue.FRONT_RIGHT)
	].build

	val updateRightRule = createRule(updateRight).name("UpdateRightRule").action [
		updateSpatial(e, SemanticSpatialPositionValue.RIGHT)
	].build

	val updateRearRightRule = createRule(updateRearRight).name("UpdateRearRightRule").action [
		updateSpatial(e, SemanticSpatialPositionValue.REAR_RIGHT)
	].build

	val updateRearRule = createRule(updateRear).name("UpdateRearRule").action [
		updateSpatial(e, SemanticSpatialPositionValue.REAR)
	].build

	val updateRearLeftRule = createRule(updateRearLeft).name("UpdateRearLeftRule").action [
		updateSpatial(e, SemanticSpatialPositionValue.REAR_LEFT)
	].build

	// Lane membership

	val createVehicleOnLaneSegmentRule = createRule(vehicleOnLaneSegmentEdgeToBeAdded).name("CreateVehicleOnLaneSegmentRule").action [
		createVehicleIsInLaneSegmentEdge(vehicle, lane)
	].build

	val removeVehicleOnLaneSegmentRule = createRule(vehicleOnLaneSegmentEdgeToBeRemoved).name("RemoveVehicleOnLaneSegmentRule").action [
		deleteEdge(e)
	].build

	// RSS violations

	val createLongitudinalRSSViolationRule = createRule(longitudinalRSSViolationEdgeToBeAdded).name("CreateLongitudinalRSSViolationRule").action [
		createTypedEdge(v1, v2, SceneGraphModelPackage.Literals.LONGITUDINAL_RSS_VIOLATION)
	].build

	val removeLongitudinalRSSViolationRule = createRule(longitudinalRSSViolationEdgeToBeRemoved).name("RemoveLongitudinalRSSViolationRule").action [
		deleteEdge(e)
	].build

	val createLateralRSSViolationRule = createRule(lateralRSSViolationEdgeToBeAdded).name("CreateLateralRSSViolationRule").action [
		createTypedEdge(v1, v2, SceneGraphModelPackage.Literals.LATERAL_RSS_VIOLATION)
	].build

	val removeLateralRSSViolationRule = createRule(lateralRSSViolationEdgeToBeRemoved).name("RemoveLateralRSSViolationRule").action [
		deleteEdge(e)
	].build

	// Model manipulation helpers

	def createDistanceEdge(Vehicle v1, Vehicle v2, SemanticDistanceValue distance) {
		val scene = v1.eContainer as SceneGraph
		val edge = scene.createChild(SceneGraphModelPackage.Literals.SCENE_GRAPH__EDGES,
			SceneGraphModelPackage.Literals.DISTANCE) as Edge
		edge.set(SceneGraphModelPackage.Literals.EDGE__SOURCE, v1)
		edge.set(SceneGraphModelPackage.Literals.EDGE__TARGET, v2)
		edge.set(SceneGraphModelPackage.Literals.DISTANCE__VALUE, distance)
		info('''Created DistanceEdge: «v1.id» -> «v2.id» («distance»)''')
	}
	
	def createSpatialEdge(Vehicle source, Vehicle target, SemanticSpatialPositionValue spatial) {
		val scene = source.eContainer as SceneGraph
		val edge = scene.createChild(SceneGraphModelPackage.Literals.SCENE_GRAPH__EDGES,
			SceneGraphModelPackage.Literals.SPATIAL_POSITION) as Edge
		edge.set(SceneGraphModelPackage.Literals.EDGE__SOURCE, source)
		edge.set(SceneGraphModelPackage.Literals.EDGE__TARGET, target)
		edge.set(SceneGraphModelPackage.Literals.SPATIAL_POSITION__VALUE, spatial)
		info('''Created SpatialEdge: «source.id» -> «target.id» («spatial»)''')
	}

	def createVehicleIsInLaneSegmentEdge(Vehicle vehicle, LaneSegment lane) {
		val scene = vehicle.eContainer as SceneGraph
		val edge = scene.createChild(SceneGraphModelPackage.Literals.SCENE_GRAPH__EDGES,
			SceneGraphModelPackage.Literals.VEHICLE_IN_LANE_SEGMENT) as Edge
		edge.set(SceneGraphModelPackage.Literals.EDGE__SOURCE, vehicle)
		edge.set(SceneGraphModelPackage.Literals.EDGE__TARGET, lane)
		info('''Created VehicleIsInLaneSegmentEdge: «vehicle.id» -> «lane.id»''')
	}

	def createTypedEdge(Node source, Node target, EClass edgeType) {
		val scene = source.eContainer as SceneGraph
		val edge = scene.createChild(SceneGraphModelPackage.Literals.SCENE_GRAPH__EDGES, edgeType) as Edge
		edge.set(SceneGraphModelPackage.Literals.EDGE__SOURCE, source)
		edge.set(SceneGraphModelPackage.Literals.EDGE__TARGET, target)
		info('''Created «edgeType.name»: «source.id» -> «target.id»''')
	}

	def updateDistance(Edge e, SemanticDistanceValue distance) {
		e.set(SceneGraphModelPackage.Literals.DISTANCE__VALUE, distance)
		info('''Updated distance: «e.source.id» -> «e.target.id» = «distance»''')
	}

	def updateSpatial(Edge e, SemanticSpatialPositionValue spatial) {
		e.set(SceneGraphModelPackage.Literals.SPATIAL_POSITION__VALUE, spatial)
		info('''Updated spatial: «e.source.id» -> «e.target.id» = «spatial»''')
	}

	def deleteEdge(Edge e) {
		info('''Removed «e.eClass.name» edge: «e.source.id» -> «e.target.id»''')
		manipulation.remove(e)
	}

}
