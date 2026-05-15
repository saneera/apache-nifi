import axios from 'axios';

const api = axios.create({baseURL: 'http://localhost:8081'});

export const getRooms = () =>
    api.get('/properties/rooms');

export const getParticipants = () =>
    api.get('/properties/participants');


export const createRoom = (roomName) =>
    api.put('/commands/create-room', {roomName});


export const addParticipant = (participantName) =>
    api.put('/commands/add-participant', {participantName});


export const addParticipantToRoom = (roomName, participantName) =>
    api.put('/commands/add-participant-to-room', {
    roomName,
    participantName
});

export const sendMessage = (roomName, participantName, message) =>
    api.put('/commands/send-message', {
    roomName,
    participantName,
    message
});
