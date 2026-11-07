'use client';

import React from 'react';
import {
  Box,
  Typography,
  Paper,
  Tooltip,
  Chip,
  Grid,
} from '@mui/material';
import CheckIcon from '@mui/icons-material/Check';
import LockIcon from '@mui/icons-material/Lock';
import HourglassEmptyIcon from '@mui/icons-material/HourglassEmpty';
import { Seat } from '../types';

interface VenueSeatMapProps {
  seats: Seat[];
  selectedSeatIds: string[];
  onToggleSeat: (seatId: string) => void;
  maxSelectionReached: boolean;
}

export default function VenueSeatMap({
  seats,
  selectedSeatIds,
  onToggleSeat,
  maxSelectionReached,
}: VenueSeatMapProps) {
  // Group seats by section
  const sections = Array.from(new Set(seats.map((s) => s.section)));

  return (
    <Box sx={{ width: '100%', maxWidth: 900, mx: 'auto', my: 3 }}>
      {/* Stage Representation */}
      <Paper
        elevation={3}
        sx={{
          py: 1.5,
          mb: 4,
          textAlign: 'center',
          background: 'linear-gradient(90deg, #4f46e5 0%, #06b6d4 100%)',
          borderRadius: 4,
          boxShadow: '0 0 25px rgba(99, 102, 241, 0.4)',
        }}
      >
        <Typography variant="subtitle1" fontWeight={700} letterSpacing={4} color="#fff">
          STAGE / PERFORMER PLATFORM
        </Typography>
      </Paper>

      {/* Seating Sections */}
      {sections.map((sectionName) => {
        const sectionSeats = seats.filter((s) => s.section === sectionName);
        const rows = Array.from(new Set(sectionSeats.map((s) => s.row))).sort();

        return (
          <Paper
            key={sectionName}
            sx={{
              p: 3,
              mb: 3,
              bgcolor: 'rgba(17, 24, 39, 0.7)',
              backdropFilter: 'blur(8px)',
              border: '1px solid rgba(255, 255, 255, 0.08)',
              borderRadius: 3,
            }}
          >
            <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
              <Typography variant="h6" fontWeight={700} color="primary.light">
                Section: {sectionName}
              </Typography>
              <Chip
                label={`$${Number(sectionSeats[0]?.price || 0).toFixed(2)} / ticket`}
                size="small"
                color="secondary"
                variant="outlined"
              />
            </Box>

            {rows.map((rowLabel) => {
              const rowSeats = sectionSeats
                .filter((s) => s.row === rowLabel)
                .sort((a, b) => Number(a.number) - Number(b.number));

              return (
                <Box
                  key={rowLabel}
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    gap: 1.2,
                    my: 1.2,
                  }}
                >
                  <Typography
                    variant="caption"
                    sx={{ width: 24, textAlign: 'right', color: 'text.secondary', fontWeight: 600 }}
                  >
                    Row {rowLabel}
                  </Typography>

                  <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', justifyContent: 'center' }}>
                    {rowSeats.map((seat) => {
                      const isSelected = selectedSeatIds.includes(seat.id);
                      const isAvailable = seat.status === 'AVAILABLE';
                      const isHeld = seat.status === 'HELD';
                      const isSold = seat.status === 'SOLD';

                      let bgColor = 'rgba(16, 185, 129, 0.15)'; // Available emerald
                      let borderColor = '#10b981';
                      let cursor = 'pointer';
                      let icon = null;

                      if (isSelected) {
                        bgColor = '#6366f1';
                        borderColor = '#818cf8';
                        icon = <CheckIcon sx={{ fontSize: 14, color: '#fff' }} />;
                      } else if (isHeld) {
                        bgColor = 'rgba(245, 158, 11, 0.2)';
                        borderColor = '#f59e0b';
                        cursor = 'not-allowed';
                        icon = <HourglassEmptyIcon sx={{ fontSize: 13, color: '#f59e0b' }} />;
                      } else if (isSold) {
                        bgColor = 'rgba(55, 65, 81, 0.5)';
                        borderColor = '#374151';
                        cursor = 'not-allowed';
                        icon = <LockIcon sx={{ fontSize: 12, color: '#6b7280' }} />;
                      }

                      return (
                        <Tooltip
                          key={seat.id}
                          title={
                            <Box sx={{ p: 0.5 }}>
                              <Typography variant="body2" fontWeight={600}>
                                {sectionName} - Row {seat.row}, Seat {seat.number}
                              </Typography>
                              <Typography variant="caption" display="block">
                                Price: ${Number(seat.price).toFixed(2)}
                              </Typography>
                              <Typography
                                variant="caption"
                                display="block"
                                sx={{
                                  color: isSold ? 'error.main' : isHeld ? 'warning.main' : 'secondary.main',
                                  fontWeight: 700,
                                }}
                              >
                                Status: {isHeld ? 'HELD (In Checkout)' : seat.status}
                              </Typography>
                            </Box>
                          }
                          arrow
                        >
                          <Box
                            onClick={() => {
                              if (isAvailable || isSelected) {
                                onToggleSeat(seat.id);
                              }
                            }}
                            sx={{
                              width: 38,
                              height: 38,
                              borderRadius: '8px',
                              display: 'flex',
                              flexDirection: 'column',
                              alignItems: 'center',
                              justifyContent: 'center',
                              bgcolor: bgColor,
                              border: `2px solid ${borderColor}`,
                              cursor: (isAvailable || isSelected) ? cursor : 'not-allowed',
                              transition: 'all 0.2s cubic-bezier(0.4, 0, 0.2, 1)',
                              transform: isSelected ? 'scale(1.12)' : 'none',
                              boxShadow: isSelected
                                ? '0 0 14px rgba(99, 102, 241, 0.7)'
                                : 'none',
                              opacity: isSold ? 0.35 : 1,
                              '&:hover': {
                                transform: (isAvailable || isSelected) ? 'scale(1.18)' : 'none',
                                zIndex: 2,
                              },
                            }}
                          >
                            {icon || (
                              <Typography variant="caption" fontWeight={700} fontSize="0.75rem">
                                {seat.number}
                              </Typography>
                            )}
                          </Box>
                        </Tooltip>
                      );
                    })}
                  </Box>
                </Box>
              );
            })}
          </Paper>
        );
      })}

      {/* Legend */}
      <Paper
        sx={{
          p: 2,
          display: 'flex',
          justifyContent: 'center',
          gap: 3,
          flexWrap: 'wrap',
          bgcolor: 'rgba(17, 24, 39, 0.5)',
          borderRadius: 2,
        }}
      >
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <Box sx={{ width: 18, height: 18, borderRadius: 1, bgcolor: 'rgba(16, 185, 129, 0.2)', border: '2px solid #10b981' }} />
          <Typography variant="caption">Available</Typography>
        </Box>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <Box sx={{ width: 18, height: 18, borderRadius: 1, bgcolor: '#6366f1', border: '2px solid #818cf8' }} />
          <Typography variant="caption">Your Selection</Typography>
        </Box>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <Box sx={{ width: 18, height: 18, borderRadius: 1, bgcolor: 'rgba(245, 158, 11, 0.2)', border: '2px solid #f59e0b' }} />
          <Typography variant="caption">Held by Buyer (10m TTL)</Typography>
        </Box>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <Box sx={{ width: 18, height: 18, borderRadius: 1, bgcolor: 'rgba(55, 65, 81, 0.5)', border: '2px solid #374151' }} />
          <Typography variant="caption">Sold</Typography>
        </Box>
      </Paper>
    </Box>
  );
}
